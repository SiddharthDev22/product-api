package com.productapi.repository

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(
        host: String = System.getenv("DB_HOST") ?: "localhost",
        port: String = System.getenv("DB_PORT") ?: "5432",
        name: String = System.getenv("DB_NAME") ?: "productdb",
        user: String = System.getenv("DB_USER") ?: "productuser",
        password: String = System.getenv("DB_PASSWORD") ?: "productpass"
    ) {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$name"
        logger.info("Connecting to database: $jdbcUrl")

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 30_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
        }

        Database.connect(HikariDataSource(config))

        transaction {
            SchemaUtils.create(ProductsTable, DiscountsTable)
            seedData()
        }

        logger.info("Database initialized successfully")
    }

    private fun seedData() {
        if (ProductsTable.selectAll().count() == 0L) {
            val products = listOf(
                Triple("prod-1", "Swedish Meatballs", "Sweden"),
                Triple("prod-2", "IKEA Chair", "Sweden"),
                Triple("prod-3", "Berlin Bread", "Germany"),
                Triple("prod-4", "BMW Model Car", "Germany"),
                Triple("prod-5", "French Croissant", "France"),
                Triple("prod-6", "Eiffel Tower Figurine", "France")
            )

            val prices = listOf(12.99, 149.99, 4.50, 89.99, 3.75, 24.99)

            products.zip(prices).forEach { (product, price) ->
                ProductsTable.insert {
                    it[id] = product.first
                    it[name] = product.second
                    it[basePrice] = price
                    it[country] = product.third
                }
            }
        }
    }
}
