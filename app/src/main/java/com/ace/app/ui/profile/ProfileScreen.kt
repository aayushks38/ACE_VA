package com.ace.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth

private val ProfileBackground = Color(0xFF03020A)
private val ProfilePurple = Color(0xFF8C52FF)
private val ProfileWhite = Color(0xFFF8F7FF)
private val ProfileGray = Color(0xFF9C98B0)
private val SignOutRed = Color(0xFFFF6B6B)

@Composable
fun ProfileScreen(
    onBackClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    val user = FirebaseAuth.getInstance().currentUser

    val userName = user?.displayName
        ?.takeIf { it.isNotBlank() }
        ?: user?.email?.substringBefore("@")
        ?: "ACE User"

    val userEmail = user?.email ?: "Google Account"

    val initial = userName
        .firstOrNull()
        ?.uppercase()
        ?: "A"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ProfileBackground)
            .systemBarsPadding()
            .padding(horizontal = 28.dp)
    ) {

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = "←",
                color = ProfileWhite,
                fontSize = 28.sp,
                modifier = Modifier
                    .clickable {
                        onBackClick()
                    }
                    .padding(end = 24.dp)
            )

            Text(
                text = "Profile",
                color = ProfileWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(65.dp))

        Box(
            modifier = Modifier
                .size(90.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(Color(0xFF211536))
                .border(
                    width = 2.dp,
                    color = ProfilePurple,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = ProfileWhite,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = userName,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = ProfileWhite,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = userEmail,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = ProfileGray,
            fontSize = 15.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(
                    width = 1.5.dp,
                    color = SignOutRed,
                    shape = RoundedCornerShape(18.dp)
                )
                .clickable {
                    FirebaseAuth.getInstance().signOut()
                    onSignOutClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Sign Out",
                color = SignOutRed,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}