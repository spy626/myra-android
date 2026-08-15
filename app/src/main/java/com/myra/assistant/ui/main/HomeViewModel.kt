package com.myra.assistant.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.myra.assistant.commands.Command
import com.myra.assistant.core.AssistantController
import com.myra.assistant.core.AssistantResult
import com.myra.assistant.core.AssistantState

class HomeViewModel(private val controller: AssistantController) : ViewModel(), AssistantController.Listener {
    private val _state = MutableLiveData(controller.state)
    val state: LiveData<AssistantState> = _state
    private val _result = MutableLiveData<Pair<Command, AssistantResult>>()
    val result: LiveData<Pair<Command, AssistantResult>> = _result
    init { controller.addListener(this) }
    override fun onStateChanged(state: AssistantState) { _state.postValue(state) }
    override fun onResult(command: Command, result: AssistantResult) { _result.postValue(command to result) }
    fun submit(text: String) = controller.processText(text)
    fun stop() = controller.stop()
    override fun onCleared() { controller.removeListener(this) }
}
