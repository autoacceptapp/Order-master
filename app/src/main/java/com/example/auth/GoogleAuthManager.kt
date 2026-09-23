package com.example.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.AppSettings
import com.example.UserAuthState
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed class AuthResult {
    data class Success(
        val email: String,
        val displayName: String?,
        val photoUrl: String?,
        val idToken: String
    ) : AuthResult()

    data class Error(val message: String) : AuthResult()
    data object Cancelled : AuthResult()
}

/**
 * Modern Google Authentication helper using Android Credential Manager API and Firebase Auth.
 */
object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"

    val userAuthState: StateFlow<UserAuthState>
        get() = AppSettings.userAuthState

    /**
     * Resolves the Web Client ID for Google Sign-In.
     * Checks strings.xml resource or falls back to standard client ID.
     */
    fun getServerClientId(context: Context): String {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resId != 0) {
            val resClientId = context.getString(resId)
            if (resClientId.isNotBlank()) {
                return resClientId
            }
        }
        return "699930864283-ordermaster.apps.googleusercontent.com"
    }

    /**
     * Launches the Google Credential Manager One-Tap / Bottom Sheet sign-in flow.
     */
    suspend fun signInWithGoogle(
        context: Context,
        serverClientId: String = getServerClientId(context),
        filterByAuthorizedAccounts: Boolean = false,
        onResult: (AuthResult) -> Unit
    ) {
        val credentialManager = CredentialManager.create(context)

        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response = credentialManager.getCredential(
                request = request,
                context = context
            )

            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                val email = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                val photoUrl = googleIdTokenCredential.profilePictureUri?.toString()

                Log.d(TAG, "Google ID token extracted for email: $email, name: $displayName")

                // Authenticate with Firebase Auth if available
                authenticateWithFirebase(idToken)

                // Sync credentials into AppSettings StateFlow and SharedPreferences
                AppSettings.updateUserAuth(
                    context = context,
                    isLoggedIn = true,
                    email = email,
                    name = displayName,
                    photoUrl = photoUrl,
                    idToken = idToken
                )

                onResult(
                    AuthResult.Success(
                        email = email,
                        displayName = displayName,
                        photoUrl = photoUrl,
                        idToken = idToken
                    )
                )
            } else {
                val err = "Unsupported credential returned: ${credential::class.java.simpleName}"
                Log.w(TAG, err)
                onResult(AuthResult.Error(err))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Google Sign-In was cancelled by the user")
            onResult(AuthResult.Cancelled)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager error: ${e.message}", e)
            onResult(AuthResult.Error(e.message ?: "Sign in failed"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Google Sign-In: ${e.message}", e)
            onResult(AuthResult.Error(e.message ?: "An unexpected error occurred"))
        }
    }

    /**
     * Seamlessly bridges Google ID token to Firebase Authentication.
     */
    private suspend fun authenticateWithFirebase(idToken: String) = withContext(Dispatchers.IO) {
        try {
            val firebaseAuth = FirebaseAuth.getInstance()
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).await()
            Log.d(TAG, "Firebase Auth succeeded with UID: ${authResult.user?.uid}")
        } catch (e: Exception) {
            // Non-fatal: user still has verified Google ID token locally
            Log.w(TAG, "Firebase Auth linking unavailable or offline: ${e.message}")
        }
    }

    /**
     * Signs out the user, clears Credential Manager session and Firebase session.
     */
    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Failed clearing CredentialManager state: ${e.message}")
        }

        try {
            FirebaseAuth.getInstance().signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Failed signing out of Firebase Auth: ${e.message}")
        }

        AppSettings.signOut(context)
    }
}
