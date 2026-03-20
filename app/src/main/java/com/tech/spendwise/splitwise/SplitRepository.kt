package com.tech.spendwise.splitwise

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.tech.spendwise.models.Settlement
import com.tech.spendwise.models.SplitExpense
import com.tech.spendwise.models.SplitGroup

/**
 * Firestore CRUD for the Splitwise feature.
 *
 * Collections:
 *  - users/{uid}/split_groups
 *  - users/{uid}/split_expenses
 *  - users/{uid}/settlements
 */
object SplitRepository {

    private const val TAG = "SplitRepository"
    private val db = FirebaseFirestore.getInstance()
    private const val USERS = "users"

    // ── Groups ─────────────────────────────────────────────────────────────

    fun saveGroup(uid: String, group: SplitGroup, onResult: (Result<String>) -> Unit) {
        val data = mapOf(
            "name" to group.name,
            "members" to group.members,
            "created_at" to group.createdAt
        )
        val doc = if (group.id.isNotEmpty()) {
            db.collection(USERS).document(uid).collection("split_groups").document(group.id)
        } else {
            db.collection(USERS).document(uid).collection("split_groups").document()
        }
        doc.set(data)
            .addOnSuccessListener { onResult(Result.success(doc.id)) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun fetchGroups(uid: String, onResult: (Result<List<SplitGroup>>) -> Unit) {
        db.collection(USERS).document(uid).collection("split_groups")
            .orderBy("created_at", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        SplitGroup(
                            id = doc.id,
                            name = doc.getString("name") ?: "",
                            members = (doc.get("members") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                            createdAt = doc.getLong("created_at") ?: 0L
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping group ${doc.id}: ${e.message}")
                        null
                    }
                }
                onResult(Result.success(list))
            }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun deleteGroup(uid: String, groupId: String, onComplete: () -> Unit = {}) {
        db.collection(USERS).document(uid).collection("split_groups")
            .document(groupId).delete()
            .addOnSuccessListener { onComplete() }
            .addOnFailureListener { Log.e(TAG, "Group delete failed: ${it.message}") }
    }

    // ── Expenses ────────────────────────────────────────────────────────────

    fun saveExpense(uid: String, expense: SplitExpense, onResult: (Result<String>) -> Unit) {
        val data = mapOf(
            "group_id" to expense.groupId,
            "description" to expense.description,
            "amount" to expense.amount,
            "paid_by" to expense.paidBy,
            "split_among" to expense.splitAmong,
            "created_at" to expense.createdAt
        )
        val doc = if (expense.id.isNotEmpty()) {
            db.collection(USERS).document(uid).collection("split_expenses").document(expense.id)
        } else {
            db.collection(USERS).document(uid).collection("split_expenses").document()
        }
        doc.set(data)
            .addOnSuccessListener { onResult(Result.success(doc.id)) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    @Suppress("UNCHECKED_CAST")
    fun fetchExpenses(uid: String, groupId: String, onResult: (Result<List<SplitExpense>>) -> Unit) {
        db.collection(USERS).document(uid).collection("split_expenses")
            .whereEqualTo("group_id", groupId)
            .orderBy("created_at", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        SplitExpense(
                            id = doc.id,
                            groupId = doc.getString("group_id") ?: "",
                            description = doc.getString("description") ?: "",
                            amount = doc.getDouble("amount") ?: 0.0,
                            paidBy = doc.getString("paid_by") ?: "",
                            splitAmong = (doc.get("split_among") as? Map<String, Any>)?.mapValues {
                                (it.value as? Number)?.toDouble() ?: 0.0
                            } ?: emptyMap(),
                            createdAt = doc.getLong("created_at") ?: 0L
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping expense ${doc.id}: ${e.message}")
                        null
                    }
                }
                onResult(Result.success(list))
            }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun deleteExpense(uid: String, expenseId: String, onComplete: () -> Unit = {}) {
        db.collection(USERS).document(uid).collection("split_expenses")
            .document(expenseId).delete()
            .addOnSuccessListener { onComplete() }
    }

    // ── Settlements ─────────────────────────────────────────────────────────

    fun saveSettlement(uid: String, settlement: Settlement, onResult: (Result<String>) -> Unit) {
        val data = mapOf(
            "group_id" to settlement.groupId,
            "from" to settlement.from,
            "to" to settlement.to,
            "amount" to settlement.amount,
            "settled_at" to settlement.settledAt
        )
        val doc = db.collection(USERS).document(uid).collection("settlements").document()
        doc.set(data)
            .addOnSuccessListener { onResult(Result.success(doc.id)) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun fetchSettlements(uid: String, groupId: String, onResult: (Result<List<Settlement>>) -> Unit) {
        db.collection(USERS).document(uid).collection("settlements")
            .whereEqualTo("group_id", groupId)
            .orderBy("settled_at", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        Settlement(
                            id = doc.id,
                            groupId = doc.getString("group_id") ?: "",
                            from = doc.getString("from") ?: "",
                            to = doc.getString("to") ?: "",
                            amount = doc.getDouble("amount") ?: 0.0,
                            settledAt = doc.getLong("settled_at") ?: 0L
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping settlement ${doc.id}: ${e.message}")
                        null
                    }
                }
                onResult(Result.success(list))
            }
    }

    /**
     * Deletes all Splitwise data for the user.
     */
    fun clearAllSplitData(uid: String, onComplete: () -> Unit = {}) {
        val collections = listOf("split_groups", "split_expenses", "settlements")
        var count = 0
        
        collections.forEach { col ->
            db.collection(USERS).document(uid).collection(col).get().addOnSuccessListener { snapshot ->
                val batch = db.batch()
                snapshot.documents.forEach { batch.delete(it.reference) }
                batch.commit().addOnCompleteListener {
                    count++
                    if (count == collections.size) onComplete()
                }
            }.addOnFailureListener {
                count++
                if (count == collections.size) onComplete()
            }
        }
    }
}
