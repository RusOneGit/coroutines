import kotlinx.coroutines.*
import okhttp3.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.logging.HttpLoggingInterceptor
import ru.netology.coroutines.dto.Author
import ru.netology.coroutines.dto.Comment
import ru.netology.coroutines.dto.Post
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val gson = Gson()
private val BASE_URL = "http://127.0.0.1:9999"
private val client = OkHttpClient.Builder()
    .addInterceptor(HttpLoggingInterceptor(::println).apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()


fun main() {
    runBlocking {
        try {
            val postsWithAuthors = getPostsWithAuthors(client)
            postsWithAuthors.forEach { postWithComments ->
                println("Post: ${postWithComments.post.content}, Author: ${postWithComments.author.name}")
                postWithComments.comments.forEach { comment ->
                    val commentAuthor = getAuthor(client, comment.authorId)
                    println("Comment: ${comment.content}, Author: ${commentAuthor.name}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}



data class PostWithComments(
    val post: Post,
    val comments: List<Comment>,
    val author: Author
)

suspend fun OkHttpClient.apiCall(url: String): Response {
    return suspendCoroutine { continuation ->
        Request.Builder()
            .url(url)
            .build()
            .let(::newCall)
            .enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
    }
}

suspend fun <T> makeRequest(url: String, client: OkHttpClient, typeToken: TypeToken<T>): T =
    withContext(Dispatchers.IO) {
        client.apiCall(url)
            .let { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw RuntimeException(response.message)
                }
                val body = response.body ?: throw RuntimeException("response body is null")
                gson.fromJson(body.string(), typeToken.type)
            }
    }

suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest("$BASE_URL/api/slow/posts", client, object : TypeToken<List<Post>>() {})

suspend fun getComments(client: OkHttpClient, id: Long): List<Comment> =
    makeRequest("$BASE_URL/api/slow/posts/$id/comments", client, object : TypeToken<List<Comment>>() {})

suspend fun getAuthor(client: OkHttpClient, authorId: Long): Author =
    makeRequest("$BASE_URL/api/authors/$authorId", client, object : TypeToken<Author>() {})

suspend fun getPostsWithAuthors(client: OkHttpClient): List<PostWithComments> {
    val posts = getPosts(client)
    return coroutineScope {
        posts.map { post ->
            async {
                val comments = getComments(client, post.id)
                val author = getAuthor(client, post.authorId)
                PostWithComments(post, comments, author)
            }
        }.awaitAll()
    }
}

