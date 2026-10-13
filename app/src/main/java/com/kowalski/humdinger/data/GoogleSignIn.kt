package com.kowalski.humdinger.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class SignInCancelledException : Exception("Sign-in cancelled")

/** Shows Google's account picker and returns an ID token for Firebase. */
object GoogleSignIn {

    /**
     * The "web client ID" comes from google-services.json once Google sign-in is
     * switched on in Firebase (see SETUP.md). Looked up at runtime so the app still
     * builds before that step is done.
     */
    @SuppressLint("DiscouragedApi")
    private fun webClientId(context: Context): String {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) {
            throw IllegalStateException(
                "Google sign-in isn't set up yet. Turn it on in Firebase and re-download google-services.json (see SETUP.md)."
            )
        }
        return context.getString(id)
    }

    /** [activityContext] must be an Activity (LocalContext inside the app is fine). */
    suspend fun getIdToken(activityContext: Context): String {
        val option = GetSignInWithGoogleOption.Builder(webClientId(activityContext)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request)
        } catch (e: GetCredentialCancellationException) {
            throw SignInCancelledException()
        }
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw IllegalStateException("Google didn't return an account. Try again?")
    }

    /** Forget the chosen Google account on this phone (used when signing out). */
    suspend fun clear(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
