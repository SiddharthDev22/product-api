package com.productapi.repository

import org.jetbrains.exposed.sql.Table

object ProductsTable : Table("products") {
    val id = varchar("id", 255)
    val name = varchar("name", 500)
    val basePrice = double("base_price")
    val country = varchar("country", 100)

    override val primaryKey = PrimaryKey(id)
}

object DiscountsTable : Table("discounts") {
    val id = integer("id").autoIncrement()
    val productId = varchar("product_id", 255).references(ProductsTable.id)
    val discountId = varchar("discount_id", 255)
    val percent = double("percent")

    override val primaryKey = PrimaryKey(id)

    // This unique constraint enforces idempotency at the DB level
    // — the same discountId cannot be applied twice to the same product
    init {
        uniqueIndex("uq_product_discount", productId, discountId)
    }
}
