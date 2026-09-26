package com.myra.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiConnectionTester {
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    suspend fun test(provider:Provider,key:String,tavilyUrl:String):Result=withContext(Dispatchers.IO){
        if(key.isBlank())return@withContext Result(false,"Enter the API key first")
        val request=when(provider){
            Provider.OPENROUTER->Request.Builder().url("https://openrouter.ai/api/v1/models").header("Authorization","Bearer $key").get().build()
            Provider.GROQ->Request.Builder().url("https://api.groq.com/openai/v1/models").header("Authorization","Bearer $key").get().build()
            Provider.DEEPSEEK->Request.Builder().url("https://api.deepseek.com/models").header("Authorization","Bearer $key").get().build()
            Provider.TAVILY->{val body=JSONObject().put("query","MYRA connection test").put("search_depth","basic").put("max_results",1).toString();Request.Builder().url(tavilyUrl.trim().ifBlank{"https://api.tavily.com/search"}).header("Authorization","Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build()}
        }
        try{client.newCall(request).execute().use{r->if(r.isSuccessful)Result(true,"${provider.label} connected ✓")else Result(false,"${provider.label}: HTTP ${r.code} — ${if(r.code==401||r.code==403)"key rejected" else if(r.code==429)"rate or credit limit reached" else "service error"}")}}catch(e:Exception){Result(false,"${provider.label}: ${e.message?:"connection failed"}")}
    }
    enum class Provider(val label:String){OPENROUTER("OpenRouter"),GROQ("Groq"),DEEPSEEK("DeepSeek"),TAVILY("Tavily")}
    data class Result(val success:Boolean,val message:String)
}
