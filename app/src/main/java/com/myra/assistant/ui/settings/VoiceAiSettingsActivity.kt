package com.myra.assistant.ui.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivityVoiceAiSettingsBinding

class VoiceAiSettingsActivity:AppCompatActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val b=ActivityVoiceAiSettingsBinding.inflate(layoutInflater);setContentView(b.root);val p=getSharedPreferences("myra",MODE_PRIVATE);val models=listOf("gemini-3.1-flash-live-preview","gemini-2.5-flash-native-audio-preview-12-2025");val voices=listOf("Aoede","Charon","Kore","Fenrir","Puck","Leda","Orus","Zephyr");val modes=listOf("GF","Professional","Assistant");b.modelSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,models);b.voiceSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,voices);b.personalitySpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,modes);b.nameInput.setText(p.getString("user_name","Friend"));b.modelSpinner.setSelection(models.indexOf(p.getString("model",models[0])).coerceAtLeast(0));b.voiceSpinner.setSelection(voices.indexOf(p.getString("voice",voices[0])).coerceAtLeast(0));b.personalitySpinner.setSelection(modes.indexOf(p.getString("personality",modes[0])).coerceAtLeast(0));b.backButton.setOnClickListener{finish()};b.saveButton.setOnClickListener{p.edit().putString("user_name",b.nameInput.text.toString().trim()).putString("model",models[b.modelSpinner.selectedItemPosition]).putString("voice",voices[b.voiceSpinner.selectedItemPosition]).putString("personality",modes[b.personalitySpinner.selectedItemPosition]).apply();Toast.makeText(this,"Voice settings saved",Toast.LENGTH_SHORT).show()}}}
