package com.productapi.repository

import com.productapi.model.Discount
import com.productapi.model.Product
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.sql.SQLException

class ProductRepository {
    private val logger = LoggerFactory.getLogger(ProductRepository::class.java)

    suspend fun getByCountry(country: String): List<Product> = newSuspendedTransaction {
        val discountsByProduct = DiscountsTable
            .selectAll()
            .groupBy { it[DiscountsTable.productId] }
            .mapValues { (_, rows) ->
                rows.map { Discount(it[DiscountsTable.discountId], it[DiscountsTable.percent]) }
            }

        ProductsTable
            .selectAll()
            .where { ProductsTable.country.lowerCase() eq country.lowercase() }
            .map { row ->
                val productId = row[ProductsTable.id]
                Product(
                    id = productId,
                    name = row[ProductsTable.name],
                    basePrice = row[ProductsTable.basePrice],
                    country = row[ProductsTable.country],
                    discounts = discountsByProduct[productId] ?: emptyList()
                )
            }
    }

    /**
     * Applies a discount idempotently and concurrency-safely.
     *
     * Strategy: INSERT ... ON CONFLICT DO NOTHING using PostgreSQL's unique constraint
     * on (product_id, discount_id). Under heavy concurrent load, only one transaction
     * will successfully insert; all others will silently skip due to the conflict.
     *
     * Returns the product after attempting the insert (with or without the new discount).
     */
    suspend fun applyDiscount(productId: String, discountId: String, percent: Double): Product =
        newSuspendedTransaction {
            // Validate product exists and lock the row to prevent concurrent modifications
            val product = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .forUpdate()  // SELECT FOR UPDATE — row-level lock
                .singleOrNull()
                ?: throw NoSuchElementException("Product not found: $productId")

            require(percent > 0 && percent < 100) {
                "Discount percent must be between 0 (exclusive) and 100 (exclusive), got: $percent"
            }

            // Attempt insert; unique constraint (product_id, discount_id) prevents duplicates.
            // ExposedSQLException with SQLState 23505 = unique_violation in PostgreSQL.
            try {
                DiscountsTable.insert {
                    it[DiscountsTable.productId] = productId
                    it[DiscountsTable.discountId] = discountId
                    it[DiscountsTable.percent] = percent
                }
                logger.info("Applied discount '$discountId' ($percent%) to product '$productId'")
            } catch (e: ExposedSQLException) {
                val sqlState = e.cause?.let { (it as? SQLException)?.sqlState }
                    ?: (e.cause?.cause as? SQLException)?.sqlState
                if (sqlState == "23505") {
                    logger.info("Discount '$discountId' already applied to product '$productId' — skipping (idempotent)")
                } else {
                    throw e
                }
            }

            // Re-fetch discounts after (potential) insert
            val discounts = DiscountsTable
                .selectAll()
                .where { DiscountsTable.productId eq productId }
                .map { Discount(it[DiscountsTable.discountId], it[DiscountsTable.percent]) }

            Product(
                id = productId,
                name = product[ProductsTable.name],
                basePrice = product[ProductsTable.basePrice],
                country = product[ProductsTable.country],
                discounts = discounts
            )
        }
}
