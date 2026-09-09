package com.ace.app.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

sealed class EmailAuthResult {
    data class Success(val userId: String, val email: String) : EmailAuthResult()
    data class Error(val message: String) : EmailAuthResult()
}

class EmailAuthClient {
    
    suspend fun signIn(email: String, password: String): EmailAuthResult {
        if (email.isBlank()) {
            return EmailAuthResult.Error("Email is required")
        }
        
        if (!isValidEmail(email)) {
            return EmailAuthResult.Error("Invalid email format")
        }
        
        if (password.isBlank()) {
            return EmailAuthResult.Error("Password is required")
        }
        
        if (password.length < 6) {
            return EmailAuthResult.Error("Password must be at least 6 characters")
        }
        
        return try {
            val authResult = FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
            val user = authResult.user
            if (user != null) {
                EmailAuthResult.Success(user.uid, user.email ?: email)
            } else {
                EmailAuthResult.Error("Authentication failed: No user returned")
            }
        } catch (e: Exception) {
            EmailAuthResult.Error(e.message ?: "Email authentication failed")
        }
    }
    
    suspend fun signUp(email: String, password: String, confirmPassword: String): EmailAuthResult {
        if (email.isBlank()) {
            return EmailAuthResult.Error("Email is required")
        }
        
        if (!isValidEmail(email)) {
            return EmailAuthResult.Error("Invalid email format")
        }
        
        if (password.isBlank()) {
            return EmailAuthResult.Error("Password is required")
        }
        
        if (password.length < 6) {
            return EmailAuthResult.Error("Password must be at least 6 characters")
        }
        
        if (password != confirmPassword) {
            return EmailAuthResult.Error("Passwords do not match")
        }
        
        return try {
            val authResult = FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password).await()
            val user = authResult.user
            if (user != null) {
                EmailAuthResult.Success(user.uid, user.email ?: email)
            } else {
                EmailAuthResult.Error("Registration failed: No user returned")
            }
        } catch (e: Exception) {
            EmailAuthResult.Error(e.message ?: "Email registration failed")
        }
    }
    
    suspend fun resetPassword(email: String): EmailAuthResult {
        if (email.isBlank()) {
            return EmailAuthResult.Error("Email is required")
        }
        
        if (!isValidEmail(email)) {
            return EmailAuthResult.Error("Invalid email format")
        }
        
        return try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(email).await()
            EmailAuthResult.Success("", email)
        } catch (e: Exception) {
            EmailAuthResult.Error(e.message ?: "Password reset failed")
        }
    }
    
    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
}

