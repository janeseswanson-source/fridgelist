package com.fridgelist.app.di

import com.fridgelist.app.voice.AndroidSpeechEngines
import com.fridgelist.app.voice.AudioRecorder
import com.fridgelist.app.voice.MediaAudioRecorder
import com.fridgelist.app.voice.SpeechEngines
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
interface VoiceModule {
    @Binds fun engines(impl: AndroidSpeechEngines): SpeechEngines

    @Binds fun recorder(impl: MediaAudioRecorder): AudioRecorder
}
