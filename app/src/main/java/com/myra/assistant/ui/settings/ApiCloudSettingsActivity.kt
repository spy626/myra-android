package com.myra.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myra.assistant.ai.ApiConnectionTester
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.databinding.ActivityApiCloudSettingsBinding
import kotlinx.coroutines.launch

class ApiCloudSettingsActivity:AppCompatActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val b=ActivityApiCloudSettingsBinding.inflate(layoutInflater);setContentView(b.root);val p=getSharedPreferences("myra",MODE_PRIVATE);val keys=ApiKeyStore(this);val tester=ApiConnectionTester()
    b.openRouterKey.setText(keys.get(ApiKeyStore.OPENROUTER));b.groqKey.setText(keys.get(ApiKeyStore.GROQ));b.geminiKey.setText(keys.get(ApiKeyStore.GEMINI));b.deepseekKey.setText(keys.get(ApiKeyStore.DEEPSEEK))
    when(p.getString("conversation_provider","gemini")){"openrouter"->b.providerOpenRouter.isChecked=true;"groq"->b.providerGroq.isChecked=true;"deepseek"->b.providerDeepseek.isChecked=true;else->b.providerGemini.isChecked=true}
    b.backButton.setOnClickListener{finish()};fun test(provider:ApiConnectionTester.Provider,key:String){b.statusText.text="Testing ${provider.label}…";lifecycleScope.launch{val r=tester.test(provider,key,"https://api.tavily.com/search");b.statusText.text=r.message;b.statusText.setTextColor(if(r.success)Color.rgb(0,230,118)else Color.rgb(255,80,110))}}
    b.testOpenRouter.setOnClickListener{test(ApiConnectionTester.Provider.OPENROUTER,b.openRouterKey.text.toString())};b.testGroq.setOnClickListener{test(ApiConnectionTester.Provider.GROQ,b.groqKey.text.toString())};b.testDeepseek.setOnClickListener{test(ApiConnectionTester.Provider.DEEPSEEK,b.deepseekKey.text.toString())};b.deepResearchButton.setOnClickListener{startActivity(Intent(this,DeepResearchSettingsActivity::class.java))}
    b.saveButton.setOnClickListener{keys.put(ApiKeyStore.OPENROUTER,b.openRouterKey.text.toString());keys.put(ApiKeyStore.GROQ,b.groqKey.text.toString());keys.put(ApiKeyStore.GEMINI,b.geminiKey.text.toString());keys.put(ApiKeyStore.DEEPSEEK,b.deepseekKey.text.toString());val provider=when(b.providerGroup.checkedRadioButtonId){b.providerOpenRouter.id->"openrouter";b.providerGroq.id->"groq";b.providerDeepseek.id->"deepseek";else->"gemini"};p.edit().putString("conversation_provider",provider).apply();Toast.makeText(this,"API configuration saved",Toast.LENGTH_SHORT).show()}
}}
