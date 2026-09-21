package com.example.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class OrderRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val orderDao = db.orderDao()

    val allOrders: Flow<List<OrderEntity>> = orderDao.getAllOrders()

    fun getOrdersByStatus(accepted: Boolean): Flow<List<OrderEntity>> {
        return orderDao.getOrdersByStatus(accepted)
    }

    fun searchOrders(query: String): Flow<List<OrderEntity>> {
        return orderDao.searchOrders(query)
    }

    suspend fun insertOrder(order: OrderEntity): Long {
        return orderDao.insertOrder(order)
    }

    suspend fun clearHistory() {
        orderDao.clearAll()
    }

    suspend fun deleteOrder(orderId: Long) {
        orderDao.deleteOrder(orderId)
    }

    suspend fun getCount(): Int {
        return orderDao.getCount()
    }

    suspend fun getAcceptedCount(): Int {
        return orderDao.getAcceptedCount()
    }

    companion object {
        @Volatile
        private var INSTANCE: OrderRepository? = null

        fun getInstance(context: Context): OrderRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = OrderRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        fun seedInitialDataIfEmpty(context: Context) {
            val repo = getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                if (repo.getCount() == 0) {
                    val now = System.currentTimeMillis()
                    repo.insertOrder(
                        OrderEntity(
                            timestamp = now - 120_000,
                            fare = 125.0,
                            pickupDistanceKm = 1.2,
                            dropDistanceKm = 5.8,
                            pickupLocation = "Lalpari River Crossing",
                            dropLocation = "City Center Mall",
                            isAccepted = true,
                            decisionReason = "Fare ₹125.0 within limits and Pickup 1.2km <= 3.0km",
                            rawText = "₹125 • 1.2 km away • Lalpari River • 5.8 km drop"
                        )
                    )
                    repo.insertOrder(
                        OrderEntity(
                            timestamp = now - 360_000,
                            fare = 42.0,
                            pickupDistanceKm = 3.6,
                            dropDistanceKm = 2.1,
                            pickupLocation = "Rail Nagar Circle",
                            dropLocation = "Sardar Chowk",
                            isAccepted = false,
                            decisionReason = "Pickup 3.6 km exceeds set maximum limit (3.0 km)",
                            rawText = "₹42 • 3.6 km away • Rail Nagar • 2.1 km drop"
                        )
                    )
                    repo.insertOrder(
                        OrderEntity(
                            timestamp = now - 900_000,
                            fare = 210.0,
                            pickupDistanceKm = 0.8,
                            dropDistanceKm = 8.4,
                            pickupLocation = "Airport Terminal 2",
                            dropLocation = "Grand Regency Hotel",
                            isAccepted = true,
                            decisionReason = "Fare ₹210.0 within limits and Pickup 0.8km <= 3.0km",
                            rawText = "₹210 • 0.8 km away • Airport T2 • 8.4 km drop"
                        )
                    )
                    repo.insertOrder(
                        OrderEntity(
                            timestamp = now - 1_800_000,
                            fare = 35.0,
                            pickupDistanceKm = 1.0,
                            dropDistanceKm = 1.5,
                            pickupLocation = "Station Road Market",
                            dropLocation = "Subhash Bridge",
                            isAccepted = false,
                            decisionReason = "Fare ₹35.0 < Min Limit ₹60.0",
                            rawText = "₹35 • 1.0 km away • Station Rd • 1.5 km drop"
                        )
                    )
                }
            }
        }
    }
}
