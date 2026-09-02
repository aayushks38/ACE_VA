package com.ace.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ace.app.ui.email.EmailLoginScreen
import com.ace.app.ui.home.HomeScreen
import com.ace.app.ui.theme.AceTheme
import com.ace.app.ui.welcome.WelcomeScreen
import com.ace.app.ui.profile.ProfileScreen

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            AceTheme {
                AceApp()
            }
        }
    }
}

@Composable
fun AceApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "welcome"
    ) {
        composable("welcome") {
            WelcomeScreen(
                onNavigateToEmail = {
                    navController.navigate("email_login")
                },
                onAuthSuccess = {
                    navController.navigate("home") {
                        popUpTo("welcome") {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable("email_login") {
            EmailLoginScreen(
                onNavigateBack = {
                    navController.navigateUp()
                },
                onAuthSuccess = {
                    navController.navigate("home") {
                        popUpTo("welcome") {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable("home") {
            HomeScreen(
                onProfileClick = {
                    navController.navigate("profile")
                }
            )
        }

        composable("profile") {
            ProfileScreen(
                onBackClick = {
                    navController.navigateUp()
                },
                onSignOutClick = {
                    navController.navigate("welcome") {
                        popUpTo(0) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}