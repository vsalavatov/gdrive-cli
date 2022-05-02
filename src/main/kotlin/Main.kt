import dev.salavatov.multifs.cloud.googledrive.*
import dev.salavatov.multifs.vfs.File
import dev.salavatov.multifs.vfs.Folder
import dev.salavatov.multifs.vfs.VFS
import dev.salavatov.multifs.vfs.VFSNode
import dev.salavatov.multifs.vfs.extensions.StreamingIO
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.nio.file.Paths
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists

val AppCredentials = GoogleAppCredentials(
    "943442256292-6bbqa85nj2scvcqbl9bkshuc336p1tal.apps.googleusercontent.com",
    "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"
)

suspend fun getGoogleDriveFS(): GoogleDriveFS {
    val auth = HttpCallbackGoogleAuthorizationRequester(AppCredentials, GoogleDriveAPI.Companion.DriveScope.General)
    val api = GoogleDriveAPI(auth)
    return GoogleDriveFS(api).also {
        it.root.listFolder()
    }
}

sealed class Command
data class Chdir(val index: Int) : Command()
data class Download(val index: Int, val destPath: String) : Command()
data class Mkfile(val name: String) : Command()
data class Upload(val destIndex: Int, val srcPath: String) : Command()
object Help : Command()

fun printHelp() {
    println(
        """
        Commands:
        * cd <index> -- change directory
        * mkfile <filename> -- create new file
        * up <index> <src path> -- upload content from <src path> to file <index>
        * dl <index> <dest path> -- download content from file <index> and put it into <dest path>
        * help -- print this help
    """.trimIndent()
    )
}

fun readCommand(): Command {
    print("> ")
    val raw = readln().removeSuffix("\n")
    if (raw.startsWith("cd ")) {
        return Chdir(raw.removePrefix("cd ").toInt())
    }
    if (raw.startsWith("dl ")) {
        val tokens = raw.removePrefix("dl ").split(" ", limit = 2)
        return Download(tokens[0].toInt(), tokens[1])
    }
    if (raw.startsWith("mkfile ")) {
        return Mkfile(raw.removePrefix("mkfile "))
    }
    if (raw.startsWith("up ")) {
        val tokens = raw.removePrefix("up ").split(" ", limit = 2)
        return Upload(tokens[0].toInt(), tokens[1])
    }
    if (raw.startsWith("help")) {
        return Help
    }
    throw Exception("unknown input: $raw")
}


fun printFolderContent(list: List<VFSNode>) {
    println("0. <go up>")
    list.forEachIndexed { index, node ->
        var msg = "${index + 1}. "
        when (node) {
            is Folder -> msg += "(dir) ${node.name}"
            is File -> msg += node.name
        }
        println(msg)
    }
    println()
}

val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

suspend fun <T> runCli(fs: VFS<out T, out Folder>)
        where T : File, T : StreamingIO {
    printHelp()
    var currentFolder: Folder = fs.root
    while (true) {
        try {
            val list = currentFolder.listFolder()
            printFolderContent(list)
            val command = readCommand()
            when (command) {
                is Chdir -> {
                    if (command.index == 0) currentFolder = currentFolder.parent
                    else {
                        val node = list[command.index - 1]
                        when (node) {
                            is Folder -> currentFolder = node
                            is File -> throw Exception("cannot chdir to file")
                        }
                    }
                }
                is Download -> {
                    val node = list[command.index - 1]
                    if (node !is File || node !is StreamingIO) throw Exception("cannot download a directory")
                    val dlStream = node.readStream()
                    val destPath = Paths.get(command.destPath)
                    if (destPath.exists()) {
                        destPath.deleteExisting()
                    }
                    val destFile = destPath.toFile()
                    while (!dlStream.isClosedForRead) {
                        dlStream.read { buffer ->
                            print("\rbytes: ${dlStream.totalBytesRead}")
                            destFile.appendBytes(buffer.moveToByteArray())
                        }
                    }
                    println("\rtotal bytes downloaded: ${dlStream.totalBytesRead}")
                }
                Help -> printHelp()
                is Mkfile -> {
                    currentFolder.createFile(command.name)
                }
                is Upload -> {
                    val node = list[command.destIndex - 1]
                    if (node !is File || node !is StreamingIO) throw Exception("cannot upload to a directory")
                    val srcStream = Paths.get(command.srcPath).toFile().readChannel()
                    val channel = ByteChannel()
                    val job = appScope.launch { node.writeStream(channel) }
                    srcStream.consumeEachBufferRange { buffer, last ->
                        print("\rbytes: ${srcStream.totalBytesRead}")
                        channel.writeFully(buffer)
                        if (last) {
                            channel.close()
                        }
                        !last
                    }
                    job.join()
                    println("\rtotal bytes uploaded: ${srcStream.totalBytesRead}")
                }
            }
        } catch (e: Throwable) {
            println("Error: $e")
        }
    }
}

suspend fun main(args: Array<String>) {
    withContext(appScope.coroutineContext) {
        val gdrive = getGoogleDriveFS()
        runCli(gdrive)
    }
}