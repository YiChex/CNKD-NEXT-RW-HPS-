package net.rwhps.server.dependent.redirections.lwjgl

import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.asm.redirections.replace.def.BasicDataRedirections
import net.rwhps.asm.redirections.replace.def.BasicFunctionRedirections
import net.rwhps.server.dependent.redirections.MainRedirections
import net.rwhps.server.dependent.redirections.fast.FastLongValueClass
import net.rwhps.server.dependent.redirections.fast.LwjglFastValueClass
import net.rwhps.server.dependent.redirections.fast.LwjglFastValueClass.screenHeight
import net.rwhps.server.dependent.redirections.fast.LwjglFastValueClass.screenWidth
import net.rwhps.server.util.annotations.NeedHelp
import net.rwhps.server.util.annotations.mark.AsmMark
import java.nio.*

//关闭傻逼格式化
//@formatter:off

/**
 *
 * @author https://github.com/3arthqu4ke
 * @author Dr (dr@der.kim)
 */
@NeedHelp(info_EN = "redirect Keyboard and Mouse")
@AsmMark.ClassLoaderCompatible
class LwjglRedirections: MainRedirections {
    private val refreshRate = System.getProperty(LwjglClassProperties.REFRESH_RATE, "100").toInt()
    private val bitsPerPixel = System.getProperty(LwjglClassProperties.BITS_PER_PIXEL, "32").toInt()
    private val jniVersion = System.getProperty(LwjglClassProperties.JNI_VERSION, "24").toInt()

    private val currentBufferSize = ThreadLocal.withInitial { 0L }
    private val startTime = System.nanoTime()


    override fun register() {
        // 覆写 lwjgl 并跳过 RW-HPS 包
        addAllReplace { className ->
            return@addAllReplace className.contains("lwjgl") && !className.contains("rwhps")
        }

        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/Display", "isCreated", "()Z"), BasicDataRedirections.BOOLEANT)
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/Display", "isVisible", "()Z"), BasicDataRedirections.BOOLEANT)
        
        redirectR(MethodTypeInfoValue("org/lwjgl/glfw/GLFW", "glfwWaitEventsTimeout", "(D)V")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            Thread.sleep((args[0] as Double * 1000L).toLong())
            null
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/glfw/GLFW", "glfwGetTime", "()D")) { _: Any, _: String, _: Class<*>, _: Array<out Any?> ->
            (System.nanoTime() - startTime) / 1000000000.0
        }

        // TODO: check this does what it's supposed to
        redirectR(MethodTypeInfoValue("org/lwjgl/glfw/GLFW", "glfwGetFramebufferSize", "(J[I[I)V")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            val width = args[1] as IntArray
            width[0] = screenWidth
            val height = args[2] as IntArray
            height[0] = screenHeight
            null
        }

        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/Display", "getWidth", "()I", LwjglFastValueClass.ScreenWidth::class.java))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/Display", "getHeight", "()I", LwjglFastValueClass.ScreenHeight::class.java))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/Display", "isFullscreen", "()Z", LwjglFastValueClass.FullScreen::class.java))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/Display", "isCloseRequested", "()Z"), BasicDataRedirections.BOOLEANF)

        redirectR(MethodTypeInfoValue("org/lwjgl/DefaultSysImplementation", "getJNIVersion", "()I"), RedirectionReplace.of(jniVersion))

        // TODO: make this configurable?
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/Display", "isActive", "()Z"), BasicDataRedirections.BOOLEANT)

        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/DisplayMode", "isFullscreenCapable", "()Z", LwjglFastValueClass.FullScreen::class.java))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/DisplayMode", "getWidth", "()I", LwjglFastValueClass.ScreenWidth::class.java))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/DisplayMode", "getHeight", "()I", LwjglFastValueClass.ScreenHeight::class.java))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/DisplayMode", "getFrequency", "()I"), RedirectionReplace.of(refreshRate))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/DisplayMode", "getBitsPerPixel", "()I"), RedirectionReplace.of(bitsPerPixel))
        redirectR(MethodTypeInfoValue("org/lwjgl/glfw/GLFW", "glfwInit", "()Z"), BasicDataRedirections.BOOLEANT)
        redirectR(MethodTypeInfoValue("org/lwjgl/Sys", "getVersion", "()Ljava/lang/String;"), RedirectionReplace.of("RW-HPS-Headless-Lwjgl"))
        redirectR(MethodTypeInfoValue("org/lwjgl/Sys", "getTimerResolution", "()J", FastLongValueClass.Long_1000_Value::class.java))
        redirectR(MethodTypeInfoValue("org/lwjgl/Sys", "getTime", "()J"), BasicFunctionRedirections.NanosToMillis)
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/GL11", "glGetTexLevelParameteri","(III)I", LwjglFastValueClass.TextureSize::class.java))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/GL11", "glGenLists","(I)I"), RedirectionReplace.of(-1))
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil\$MemoryAllocator", "malloc", "(J)J"), RedirectionReplace.of(1L))
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil\$MemoryAllocator", "calloc", "(J)J"), RedirectionReplace.of(1L))
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil\$MemoryAllocator", "realloc", "(J)J"), RedirectionReplace.of(1L))
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil\$MemoryAllocator", "realloc", "(JJ)J"), RedirectionReplace.of(1L))
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil\$MemoryAllocator", "aligned_alloc", "(JJ)J"), RedirectionReplace.of(1L))

        // blaze3d RenderTarget
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/GL30", "glCheckFramebufferStatus", "(I)I"), RedirectionReplace.of(36053))

        // blaze3d NativeImage
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "nmemAlloc", "(J)J"), RedirectionReplace.of(1L))

        // TODO: because MemoryUtil and the Buffers are actually being used,
        //  redirect all methods inside those to return proper Buffers?
        //  - ignore list?
        // I WISH WE COULD SUBCLASS BUFFERS WTF
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memByteBuffer", "(JI)Ljava/nio/ByteBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            ByteBuffer.wrap(ByteArray(args[1] as Int))
        }

        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memAlloc", "(I)Ljava/nio/ByteBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            ByteBuffer.wrap(ByteArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memRealloc", "(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            ByteBuffer.wrap(ByteArray(args[1] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryStack", "mallocInt", "(I)Ljava/nio/IntBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            IntBuffer.wrap(IntArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/BufferUtils", "createIntBuffer", "(I)Ljava/nio/IntBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            IntBuffer.wrap(IntArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/BufferUtils", "createFloatBuffer", "(I)Ljava/nio/FloatBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            FloatBuffer.wrap(FloatArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "createIntBuffer", "(I)Ljava/nio/IntBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            IntBuffer.wrap(IntArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memAllocFloat", "(I)Ljava/nio/FloatBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            FloatBuffer.wrap(FloatArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/BufferUtils", "createByteBuffer", "(I)Ljava/nio/ByteBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            ByteBuffer.wrap(ByteArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/BufferUtils", "createDoubleBuffer", "(I)Ljava/nio/DoubleBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            DoubleBuffer.wrap(DoubleArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memAllocInt", "(I)Ljava/nio/IntBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            IntBuffer.wrap(IntArray(args[0] as Int shl 2))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memAllocLong", "(I)Ljava/nio/LongBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            LongBuffer.wrap(LongArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memAllocDouble", "(I)Ljava/nio/DoubleBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            DoubleBuffer.wrap(DoubleArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memAllocShort", "(I)Ljava/nio/ShortBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            ShortBuffer.wrap(ShortArray(args[0] as Int))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryStack", "malloc", "(I)Ljava/nio/ByteBuffer;")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            ByteBuffer.wrap(ByteArray(args[0] as Int))
        }

        // TODO: this is really bad...
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/GL15", "glBufferData", "(IJI)V")) { _: Any, _: String, _: Class<*>, args: Array<out Any?> ->
            currentBufferSize.set(args[1] as Long?)
            null
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/GL15", "glMapBuffer", "(II)Ljava/nio/ByteBuffer;")) { _: Any, _: String, _: Class<*>, _: Array<out Any?> ->
            ByteBuffer.wrap(ByteArray(currentBufferSize.get().toInt()))
        }
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memAddress", "(Ljava/nio/ByteBuffer;)J"), RedirectionReplace.of(1L))
        redirectR(STBIImageRedirection.DESC, STBIImageRedirection.INSTANCE)
        redirectR(MemASCIIRedirection.DESC, MemASCIIRedirection.INSTANCE)

        // act as if we compiled a shader program (blaze3d program)
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/GL20", "glGetShaderi", "(II)I"), RedirectionReplace.of(1))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/GL20", "glCreateProgram", "()I"), RedirectionReplace.of(1))
        redirectR(MethodTypeInfoValue("org/lwjgl/opengl/GL20", "glGetProgrami", "(II)I"), RedirectionReplace.of(1))
        redirectR(MethodTypeInfoValue("org/lwjgl/openal/ALC10", "alcOpenDevice", "(Ljava/lang/CharSequence;)J"), RedirectionReplace.of(1L))
        redirectR(MethodTypeInfoValue("org/lwjgl/system/MemoryUtil", "memSlice", "(Ljava/nio/ByteBuffer;II)Ljava/nio/ByteBuffer;"), BasicDataRedirections.NULL)
    }

}