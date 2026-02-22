# Product API

A Kotlin/Ktor REST service that manages products with country-based VAT pricing and idempotent, concurrency-safe discount application.

---

## 🚀 Build & Run

### Option 1: Docker Compose (recommended)

```bash
git clone <repo-url>
cd product-api
docker compose up --build
```

The API will be available at `http://localhost:8080`.

### Option 2: Local with Gradle

**Prerequisites:** JDK 17+, PostgreSQL running locally.

1. Start PostgreSQL and create the database:

```bash
psql -U postgres -c "CREATE DATABASE productdb;"
psql -U postgres -c "CREATE USER productuser WITH PASSWORD 'productpass';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE productdb TO productuser;"
```

2. Run the application:

```bash
./gradlew run
```

### Running Tests

Tests require a running PostgreSQL instance (uses same default credentials):

```bash
./gradlew test
```

---

## 📡 Example curl Commands

### Get all products for a country

```bash
# Sweden (25% VAT)
curl -s "http://localhost:8080/products?country=Sweden" | jq

# Germany (19% VAT)
curl -s "http://localhost:8080/products?country=Germany" | jq

# France (20% VAT)
curl -s "http://localhost:8080/products?country=France" | jq
```

### Apply a discount to a product

```bash
# Apply a 10% discount to product prod-1
curl -s -X PUT "http://localhost:8080/products/prod-1/discount" \
  -H "Content-Type: application/json" \
  -d '{"discountId": "SUMMER10", "percent": 10.0}' | jq

# Apply the same discount again — idempotent, no side effects
curl -s -X PUT "http://localhost:8080/products/prod-1/discount" \
  -H "Content-Type: application/json" \
  -d '{"discountId": "SUMMER10", "percent": 10.0}' | jq
```

### Concurrency test with parallel curl requests

```bash
# Fire 20 concurrent requests with the same discountId — only one will persist
for i in {1..20}; do
  curl -s -X PUT "http://localhost:8080/products/prod-2/discount" \
    -H "Content-Type: application/json" \
    -d '{"discountId": "CONCTEST", "percent": 15.0}' &
done
wait

# Verify discount appears exactly once
curl -s "http://localhost:8080/products?country=Sweden" | jq '.[].discounts'
```

---

## 💡 Pre-seeded Products

| ID | Name | Base Price | Country |
|----|------|-----------|---------|
| prod-1 | Swedish Meatballs | 12.99 | Sweden |
| prod-2 | IKEA Chair | 149.99 | Sweden |
| prod-3 | Berlin Bread | 4.50 | Germany |
| prod-4 | BMW Model Car | 89.99 | Germany |
| prod-5 | French Croissant | 3.75 | France |
| prod-6 | Eiffel Tower Figurine | 24.99 | France |

## 📐 Price Formula

```
finalPrice = basePrice × (1 - totalDiscount%) × (1 + VAT%)
```

| Country | VAT |
|---------|-----|
| Sweden | 25% |
| Germany | 19% |
| France | 20% |
