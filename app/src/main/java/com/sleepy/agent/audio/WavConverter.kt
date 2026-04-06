package com.sleepy.agent.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts raw PCM16 audio to WAV format.
 * Gemma 4 E2B's miniaudio decoder expects WAV format with proper headers.
 */
object WavConverter {
    
    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 1 // Mono
    private const val BITS_PER_SAMPLE = 16
    
    /**
     * Converts raw PCM16 audio data to WAV format with proper headers.
     * 
     * @param pcmData Raw PCM16 little-endian audio data
     * @return WAV formatted byte array with RIFF headers
     */
    fun pcmToWav(pcmData: ByteArray): ByteArray {
        if (pcmData.isEmpty()) {
            return byteArrayOf()
        }
        
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize // Header size (44) - 8 for "RIFF" and size field
        
        val output = ByteArrayOutputStream()
        
        // RIFF chunk descriptor
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        output.writeInt(totalSize)
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        
        // fmt sub-chunk
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        output.writeInt(16) // Subchunk size (16 for PCM)
        output.writeShort(1) // Audio format (1 = PCM)
        output.writeShort(CHANNELS.toShort())
        output.writeInt(SAMPLE_RATE)
        output.writeInt(byteRate)
        output.writeShort(blockAlign.toShort())
        output.writeShort(BITS_PER_SAMPLE.toShort())
        
        // data sub-chunk
        output.write("data".toByteArray(Charsets.US_ASCII))
        output.writeInt(dataSize)
        output.write(pcmData)
        
        return output.toByteArray()
    }
    
    /**
     * Check if audio data already has WAV headers (starts with "RIFF")
     */
    fun isWav(data: ByteArray): Boolean {
        return data.size >= 4 && 
               data[0] == 'R'.code.toByte() && 
               data[1] == 'I'.code.toByte() && 
               data[2] == 'F'.code.toByte() && 
               data[3] == 'F'.code.toByte()
    }
    
    private fun ByteArrayOutputStream.writeInt(value: Int) {
        val buffer = ByteBuffer.allocate(4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(value)
        write(buffer.array())
    }
    
    private fun ByteArrayOutputStream.writeShort(value: Short) {
        val buffer = ByteBuffer.allocate(2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(value)
        write(buffer.array())
    }
}
