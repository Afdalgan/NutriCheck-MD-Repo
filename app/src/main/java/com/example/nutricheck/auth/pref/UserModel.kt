package com.example.nutricheck.auth.pref

data class UserModel(
    val email: String,
    val token: String,
    val isLogin: Boolean,
    val userId: String // Tambahkan properti ini
)
