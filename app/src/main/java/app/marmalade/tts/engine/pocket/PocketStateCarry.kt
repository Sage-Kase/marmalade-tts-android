package app.marmalade.tts.engine.pocket

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * One set of state tensors, and whatever has to be closed once nothing uses
 * them any more: the `OrtSession.Result` that produced them, or the tensor
 * wrappers bound over an init [PocketStates]. Reference-counted, so a
 * snapshot (the voice-prompt cache) can share a frame with the live carry
 * without copying a byte.
 */
class StateFrame(
    val tensors: Map<String, OnnxTensor>,
    private val owner: AutoCloseable,
) {
    private var refs = 1

    fun retain(): StateFrame {
        check(refs > 0) { "state frame already closed" }
        refs++
        return this
    }

    fun release() {
        check(refs > 0) { "state frame released twice" }
        if (--refs == 0) owner.close()
    }
}

/**
 * Zero-copy state carry for Pocket's stateful graphs (issue #15 "A").
 *
 * The copying path ([updateStatesFromResult]) reads every state output with
 * `OnnxTensor.getFloatBuffer()`, which in ORT 1.26 allocates a NEW heap
 * array of the tensor's size and copies into it, then copies that into the
 * slot's direct buffer: for flow_lm_main's six 8 MB KV caches that is 49 MB
 * of fresh garbage and two copies per AR frame, and 16 MB per decoder run.
 *
 * Here a run's output tensors are passed straight back as the next run's
 * inputs, and the Result that owns them stays open until the run after that
 * has finished (ORT never writes to a graph input, so a frame stays valid
 * while anything references it). Nothing is copied in Java.
 *
 * Not thread-safe; one carry per AR loop or decode.
 */
class PocketStateCarry(private var frame: StateFrame) : AutoCloseable {

    /** Put the current state tensors into [inputs], one per manifest entry. */
    fun bind(manifest: List<PocketBundle.StateSpec>, inputs: MutableMap<String, OnnxTensor>) {
        for (spec in manifest) {
            inputs[spec.inputName] = frame.tensors[spec.inputName]
                ?: error("state frame missing ${spec.inputName}")
        }
    }

    /**
     * The outputs of [result] become the next run's inputs. The carry now
     * owns [result] (closed once nothing references it); the caller must
     * not close it.
     */
    fun advance(manifest: List<PocketBundle.StateSpec>, result: OrtSession.Result) {
        val next = try {
            manifest.associate { spec ->
                spec.inputName to (result.get(spec.outputName).orElseThrow {
                    IllegalStateException("Session did not return ${spec.outputName}")
                } as OnnxTensor)
            }
        } catch (t: Throwable) {
            result.close()
            throw t
        }
        val old = frame
        frame = StateFrame(next, result)
        old.release()
    }

    /** The current frame, shared; [StateFrame.release] it when done. */
    fun snapshot(): StateFrame = frame.retain()

    override fun close() = frame.release()

    companion object {
        /**
         * A frame over [states]' current buffers. The slots are only read
         * (ORT never writes graph inputs), so one init [PocketStates] can
         * seed every chunk without being refilled.
         */
        fun bindSlots(
            env: OrtEnvironment,
            manifest: List<PocketBundle.StateSpec>,
            states: PocketStates,
        ): StateFrame {
            val tensors = LinkedHashMap<String, OnnxTensor>(manifest.size)
            try {
                for (spec in manifest) {
                    val slot = states[spec.inputName] ?: error("State missing for ${spec.inputName}")
                    tensors[spec.inputName] = slot.bindAsTensor(env)
                }
            } catch (t: Throwable) {
                tensors.values.forEach { it.close() }
                throw t
            }
            return StateFrame(tensors) { tensors.values.forEach { it.close() } }
        }
    }
}
