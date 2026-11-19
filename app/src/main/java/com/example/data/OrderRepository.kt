package com.example.data

import android.content.Context
import com.example.LicenseManager
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

class OrderRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val orderDao = db.orderDao()
    private val hasPrunedExpiredThisSession = AtomicBoolean(false)

    val allOrders: Flow<List<OrderEntity>> = orderDao.getAllOrders()

    fun getOrdersByStatus(accepted: Boolean): Flow<List<OrderEntity>> {
        return orderDao.getOrdersByStatus(accepted)
    }

    fun searchOrders(query: String): Flow<List<OrderEntity>> {
        return orderDao.searchOrders(query)
    }

    /**
     * Inserts a new order history record and performs session-throttled 30-day age pruning
     * plus count capping so Room storage stays bounded over months of daily shifts.
     */
    suspend fun insertOrder(order: OrderEntity): Long {
        pruneExpiredOrdersIfNeeded()
        val insertedId = orderDao.insertOrder(order)
        orderDao.pruneOldOrders(500)
        return insertedId
    }

    /**
     * Deletes order history rows older than 30 days once per app session.
     */
    suspend fun pruneExpiredOrdersIfNeeded(nowMs: Long = LicenseManager.getCurrentServerTimeMs()): Int {
        if (!hasPrunedExpiredThisSession.compareAndSet(false, true)) {
            return 0
        }
        return try {
            val cutoffMs = (nowMs - RETENTION_PERIOD_MS).coerceAtLeast(0L)
            orderDao.pruneOrdersOlderThan(cutoffMs)
        } catch (t: Throwable) {
            hasPrunedExpiredThisSession.set(false)
            0
        }
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
        const val RETENTION_PERIOD_MS = 30L * 86_400_000L

        @Volatile
        private var INSTANCE: OrderRepository? = null

        fun getInstance(context: Context): OrderRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = OrderRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
