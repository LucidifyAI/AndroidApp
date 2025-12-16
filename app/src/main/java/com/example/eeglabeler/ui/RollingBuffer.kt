package com.example.eeglabeler.ui
class RollingBuffer(val channelCount: Int, val capacity: Int) {
    private val buffer = ArrayList<FloatArray>(capacity)
    private var writeIndex = 0
    var size = 0
        private set

    fun append(data: FloatArray) {
        if (data.size != channelCount) return
        if (size < capacity) {
            buffer.add(data.copyOf())
            size++
        } else {
            buffer[writeIndex] = data.copyOf()
            writeIndex = (writeIndex + 1) % capacity
        }
    }

    fun contents(): List<FloatArray> {
        return if (size < capacity) {
            buffer.toList()
        } else {
            buffer.drop(writeIndex) + buffer.take(writeIndex)
        }
    }
}

