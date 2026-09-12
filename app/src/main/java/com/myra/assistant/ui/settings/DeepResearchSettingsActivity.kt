package com.myra.assistant.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myra.assistant.ai.ApiConnectionTester
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.databinding.ActivityDeepResearchSettingsBinding
import kotlinx.coroutines.launch

class DeepResearchSettingsActivity:AppCompatActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val b=ActivityDeepResearchSettingsBinding.inflate(layoutInflater);setContentView(b.root);val p=getSharedPreferences("myra",MODE_PRIVATE);val keys=ApiKeyStore(this);var depth=p.getString("research_depth","basic")?:"basic";b.apiKey.setText(keys.get(ApiKeyStore.TAVILY));b.apiUrl.setText(p.getString("tavily_api_url","https://api.tavily.com/search"));b.depthChoice.text=if(depth=="advanced")"Advanced (2 credits · deeper)" else "Basic (1 credit · faster)"
    b.backButton.setOnClickListener{finish()};b.depthChoice.setOnClickListener{val labels=arrayOf("Basic (1 credit · faster)","Advanced (2 credits · deeper)");AlertDialog.Builder(this).setTitle("Research depth").setSingleChoiceItems(labels,if(depth=="advanced")1 else 0){d,w->depth=if(w==1)"advanced" else "basic";b.depthChoice.text=labels[w];d.dismiss()}.show()};b.testButton.setOnClickListener{b.statusText.text="Testing Tavily…";lifecycleScope.launch{val r=ApiConnectionTester().test(ApiConnectionTester.Provider.TAVILY,b.apiKey.text.toString(),b.apiUrl.text.toString());b.statusText.text=r.message;b.statusText.setTextColor(if(r.success)Color.rgb(0,230,118)else Color.rgb(255,80,110))}};b.saveButton.setOnClickListener{keys.put(ApiKeyStore.TAVILY,b.apiKey.text.toString());p.edit().putString("tavily_api_url",b.apiUrl.text.toString().trim()).putString("research_depth",depth).apply();Toast.makeText(this,"Deep Research settings saved",Toast.LENGTH_SHORT).show()}
}}
