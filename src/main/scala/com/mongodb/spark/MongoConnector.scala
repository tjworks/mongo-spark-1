/*
 * Copyright 2016 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.spark

import java.io.{Closeable, Serializable}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

import org.apache.spark.api.java.function.{Function => JFunction}
import org.apache.spark.{SparkConf, SparkContext}

import org.bson.codecs.configuration.CodecRegistry
import com.mongodb.client.{MongoCollection, MongoDatabase}
import com.mongodb.spark.config.{MongoCollectionConfig, ReadConfig, WriteConfig}
import com.mongodb.spark.connection.{DefaultMongoClientFactory, MongoClientCache}
import com.mongodb.{MongoClient, ServerAddress}

/**
 * The MongoConnector companion object
 *
 * @since 1.0
 */
object MongoConnector {

  /**
   * Creates a MongoConnector using the [[ReadConfig.mongoURIProperty]] from the `sparkConf`.
   *
   * @param sparkContext the Spark context
   * @return the MongoConnector
   */
  def apply(sparkContext: SparkContext): MongoConnector = apply(sparkContext.getConf)

  /**
   * Creates a MongoConnector using the [[ReadConfig.mongoURIProperty]] from the `sparkConf`.
   *
   * @param sparkConf the Spark configuration.
   * @return the MongoConnector
   */
  def apply(sparkConf: SparkConf): MongoConnector = {
    require(sparkConf.contains(mongoReadURIProperty), s"Missing '$mongoReadURIProperty' property from sparkConfig")
    MongoConnector(sparkConf.get(mongoReadURIProperty))
  }

  /**
   * Creates a MongoConnector
   *
   * @param connectionString the connection string (`uri`)
   * @return the MongoConnector
   */
  def apply(connectionString: String): MongoConnector = MongoConnector(DefaultMongoClientFactory(connectionString))

  private[spark] val mongoReadURIProperty: String = s"${ReadConfig.configPrefix}${ReadConfig.mongoURIProperty}"
  private[spark] val mongoWriteURIProperty: String = s"${ReadConfig.configPrefix}${ReadConfig.mongoURIProperty}"
  private[spark] val mongoClientKeepAlive = Duration(10, TimeUnit.SECONDS) // scalastyle:ignore

  private val mongoClientCache = new MongoClientCache(mongoClientKeepAlive)

  Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
    def run() {
      mongoClientCache.shutdown()
    }
  }))
}

/**
 * The MongoConnector
 *
 * Connects Spark to MongoDB
 *
 * @param mongoClientFactory the factory that can be used to create a MongoClient
 * @since 1.0
 */
case class MongoConnector(mongoClientFactory: MongoClientFactory)
    extends Serializable with Closeable with Logging {

  /**
   * Execute some code on a `MongoClient`
   *
   * *Note:* The MongoClient is reference counted and loaned to the `code` method and should only be used inside that function.
   *
   * @param code the code block that is passed
   * @tparam T the result of the code function
   * @return the result
   */
  def withMongoClientDo[T](code: MongoClient => T): T = withMongoClientDo(code, None)

  /**
   * Execute some code on a `MongoClient`
   *
   * *Note:* The MongoClient is reference counted and loaned to the `code` method and should only be used inside that function.
   *
   * @param code the code block that is executed
   * @param serverAddress the optional serverAddress to connect to
   * @tparam T the result of the code function
   * @return the result
   */
  def withMongoClientDo[T](code: MongoClient => T, serverAddress: Option[ServerAddress]): T = {
    val client = MongoConnector.mongoClientCache.acquire(serverAddress, mongoClientFactory)
    try {
      code(client)
    } finally {
      MongoConnector.mongoClientCache.release(client)
    }
  }

  /**
   * Execute some code on a database
   *
   * *Note:* The `MongoDatabase` is reference counted and loaned to the `code` method and should only be used inside that function.
   *
   * @param config the [[MongoCollectionConfig]] determining which database to connect to
   * @param code the code block that is executed
   * @tparam T the result of the code function
   * @return the result
   */
  def withDatabaseDo[T](config: MongoCollectionConfig, code: MongoDatabase => T): T = withDatabaseDo(config, code, None)

  /**
   * Execute some code on a database
   *
   * *Note:* The `MongoDatabase` is reference counted and loaned to the `code` method and should only be used inside that function.
   *
   * @param config the [[MongoCollectionConfig]] determining which database to connect to
   * @param code the code block that is executed
   * @param serverAddress the optional serverAddress to connect to
   * @tparam T the result of the code function
   * @return the result
   */
  def withDatabaseDo[T](config: MongoCollectionConfig, code: MongoDatabase => T, serverAddress: Option[ServerAddress]): T =
    withMongoClientDo({ client => code(client.getDatabase(config.databaseName)) }, serverAddress)

  /**
   * Execute some code on a collection
   *
   * *Note:* The `MongoCollection` is reference counted and loaned to the `code` method and should only be used inside that function.
   *
   * @param config the [[MongoCollectionConfig]] determining which database and collection to connect to
   * @param code the code block that is executed
   * @tparam T the result of the code function
   * @return the result
   */
  def withCollectionDo[D, T](config: MongoCollectionConfig, code: MongoCollection[D] => T)(implicit ct: ClassTag[D]): T =
    withCollectionDo[D, T](config, code, None)

  /**
   * Execute some code on a collection
   *
   * *Note:* The `MongoCollection` is reference counted and loaned to the `code` method and should only be used inside that function.
   *
   * @param config the [[MongoCollectionConfig]] determining which database and collection to connect to
   * @param code the code block that is executed
   * @param serverAddress the optional serverAddress to connect to
   * @tparam T the result of the code function
   * @return the result
   */
  def withCollectionDo[D, T](config: MongoCollectionConfig, code: MongoCollection[D] => T,
                             serverAddress: Option[ServerAddress])(implicit ct: ClassTag[D]): T = {
    withDatabaseDo(config, { db =>
      val collection = db.getCollection[D](config.collectionName, classTagToClassOf(ct))
      code(config match {
        case writeConfig: WriteConfig => collection.withWriteConcern(writeConfig.writeConcern)
        case readConfig: ReadConfig   => collection.withReadConcern(readConfig.readConcern).withReadPreference(readConfig.readPreference)
        case _                        => collection
      })
    }, serverAddress)
  }

  private[spark] def codecRegistry: CodecRegistry = withMongoClientDo({ client => client.getMongoClientOptions.getCodecRegistry })

  override def close(): Unit = MongoConnector.mongoClientCache.shutdown()

}
