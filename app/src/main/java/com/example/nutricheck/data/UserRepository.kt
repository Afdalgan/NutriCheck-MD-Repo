package com.example.nutricheck.data

import android.content.Context
import com.example.nutricheck.auth.pref.UserModel
import com.example.nutricheck.auth.pref.UserPreference
import com.example.nutricheck.data.database.ArticleDao
import com.example.nutricheck.data.entity.ArticleEntity
import com.example.nutricheck.data.response.*
import com.example.nutricheck.data.retrofit.ApiConfig
import com.example.nutricheck.data.retrofit.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.await
import java.io.IOException

class UserRepository private constructor(
    private val apiService: ApiService,
    private val userPreference: UserPreference,
    private val articleDao: ArticleDao
) {
    suspend fun getArticlesFromApi(): List<ArticleEntity> {
        return try {
            val token = getToken()
            val apiService = ApiConfig.getApiService(token)
            val response = apiService.getNews().execute().body()

            response?.data?.mapNotNull { article ->
                article?.let {
                    ArticleEntity(
                        id = it.id ?: "",
                        title = it.title ?: "",
                        description = it.description ?: "",
                        image = it.image,
                        url = it.url ?: "",
                        categories = it.categories?.joinToString(",") ?: ""
                    )
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getArticles(): Flow<List<ArticleEntity>> = flow {
        // First, get local cached articles
        val localArticles = withContext(Dispatchers.IO) {
            articleDao.getAllArticles().first()
        }
        emit(localArticles)

        try {
            val apiArticles = getArticlesFromApi()

            if (apiArticles.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    articleDao.clearArticles()
                    articleDao.insertArticles(apiArticles)
                }

                emit(apiArticles)
            }
        } catch (e: Exception) {
            emit(localArticles)
        }
    }.flowOn(Dispatchers.IO)

    fun login(email: String, password: String): Flow<Result<LoginResponse>> = flow {
        emit(Result.Loading)
        try {
            val loginRequest = LoginRequest(email, password)
            val response = apiService.loginUser(loginRequest).await()
            emit(Result.Success(response))

            response.data?.let {
                saveSession(
                    UserModel(
                        email = it.user?.email.orEmpty(),
                        token = it.token.orEmpty(),
                        isLogin = true,
                        userId = it.user?.id.orEmpty()
                    )
                )
            }
        } catch (e: IOException) {
            emit(Result.Error("Network error: ${e.message}"))
        } catch (e: Exception) {
            emit(Result.Error("Unexpected error: ${e.message}"))
        }
    }


    suspend fun searchMeals(foodName: String): AddResponse {
        return withContext(Dispatchers.IO) {
            apiService.searchMeals(foodName).execute().body() ?: AddResponse()
        }
    }



    fun fetchUserBMI(userId: String): Flow<Result<UserResponse>> = flow {
        emit(Result.Loading)
        try {
            val response = apiService.getUserBMI(userId).await()
            val bmiData = response.data?.bmi

            if (bmiData != null) {
                emit(Result.Success(response))
            } else {
                emit(Result.Error("Data BMI tidak ditemukan"))
            }
        } catch (e: IOException) {
            emit(Result.Error("Network error: ${e.message}"))
        } catch (e: Exception) {
            emit(Result.Error("Unexpected error: ${e.message}"))
        }
    }.catch { e ->
        emit(Result.Error("Flow caught an exception: ${e.localizedMessage}"))
    }

    fun searchMeals(foodName: String?): AddResponse {
        return apiService.searchMeals(foodName).execute().body()!!
    }

    suspend fun saveSession(user: UserModel) {
        userPreference.saveSession(user)
    }

    fun getSession(): Flow<UserModel> {
        return userPreference.getSession()
    }

    suspend fun logout() {
        userPreference.logout()
    }

    suspend fun getToken(): String {
        val token = userPreference.getSession().first().token
        if (token.isEmpty()) {
            throw IllegalStateException("Token tidak ditemukan. Pengguna mungkin belum login.")
        }
        return token
    }

    companion object {
        @Volatile
        private var instance: UserRepository? = null
        fun getInstance(
            apiService: ApiService,
            userPreference: UserPreference,
            articleDao: ArticleDao
        ): UserRepository =
            instance ?: synchronized(this) {
                instance ?: UserRepository(apiService, userPreference, articleDao).also { instance = it }
            }
    }
}

class ResourceProvider(private val context: Context) {
    fun getString(resId: Int): String {
        return context.getString(resId)
    }
}

