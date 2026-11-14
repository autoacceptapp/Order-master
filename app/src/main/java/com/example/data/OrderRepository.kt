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

        @Suppress("UNUSED_PARAMETER")
        fun seedInitialDataIfEmpty(context: Context) {
            // No-op: Fresh installs start with an empty, authentic Room order history.
        }
    }
}
