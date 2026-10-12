package com.sshtool.ui.filetransfer

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.sshtool.R
import com.sshtool.databinding.FragmentFileTransferBinding
import com.sshtool.ssh.FileTransferDirection
import com.sshtool.ssh.FileTransferState
import com.sshtool.ssh.RemoteFileEntry
import com.sshtool.ssh.SSHConnectionState
import com.sshtool.ui.terminal.TerminalViewModel
import com.sshtool.ui.MainActivity
import com.sshtool.util.UserMessage
import com.sshtool.util.UserMessageCarrier
import com.sshtool.util.resolveMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browses and transfers files through the already authenticated terminal session. */
class FileTransferFragment : Fragment() {

    private var _binding: FragmentFileTransferBinding? = null
    private val binding get() = _binding!!

    private val args: FileTransferFragmentArgs by navArgs()
    private val viewModel: TerminalViewModel by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity() as MainActivity).terminalViewModel
    }
    private val pickerState: FileTransferPickerStateViewModel by activityViewModels()

    private val fileAdapter = RemoteFileAdapter(::openEntry)
    private var currentPath = "."
    private var currentEntries: List<RemoteFileEntry> = emptyList()
    private var directoryJob: Job? = null
    private var entryOpenJob: Job? = null
    private var entryOpenGeneration = 0L
    private var uploadCheckJob: Job? = null
    private var pendingDownloadToken: String? = null
    private var overwriteDialog: AlertDialog? = null
    private var isConnected = false
    private var isTransferRunning = false
    private var isTransferRequestPending = false
    private var lastTransferState: FileTransferState? = null
    private var isLeavingFileTransfer = false

    private val chooseUpload = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareUpload(uri)
    }

    private val chooseDownloadDestination = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val token = pendingDownloadToken.also { pendingDownloadToken = null }
        val entry = pickerState.consumeDownload(args.sessionId, token)
        when {
            destination == null -> Unit
            entry == null -> {
                // ACTION_CREATE_DOCUMENT always creates a new document rather
                // than overwriting an existing one. Do not leave that new,
                // empty document behind when Android had to recreate us while
                // the system picker was open.
                discardCreatedDownload(destination)
                showToast(R.string.file_transfer_download_request_expired)
            }
            !canStartTransfer() -> discardCreatedDownload(destination)
            else -> {
                markTransferRequestPending()
                viewModel.downloadFile(
                    args.sessionId,
                    entry.path,
                    destination,
                    entry.size.takeIf { it >= 0 }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentPath = pickerState.currentPath(args.sessionId) ?: "."
        isTransferRequestPending = savedInstanceState?.getBoolean(
            STATE_TRANSFER_REQUEST_PENDING
        ) ?: false
        pendingDownloadToken = savedInstanceState?.getString(STATE_PENDING_DOWNLOAD_TOKEN)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFileTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isLeavingFileTransfer = false
        setupViews()
        observeSession()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_TRANSFER_REQUEST_PENDING, isTransferRequestPending)
        // The raw entry remains only in the activity ViewModel; this token is
        // harmless if Android restores it after the process was killed.
        outState.putString(STATE_PENDING_DOWNLOAD_TOKEN, pendingDownloadToken)
        super.onSaveInstanceState(outState)
    }

    private fun setupViews() = with(binding) {
        toolbar.setNavigationOnClickListener { leaveFileTransfer() }
        fileList.layoutManager = LinearLayoutManager(requireContext())
        fileList.adapter = fileAdapter
        fileList.setHasFixedSize(true)

        btnRefresh.setOnClickListener { loadDirectory(currentPath) }
        swipeRefresh.setOnRefreshListener { loadDirectory(currentPath) }
        btnParentDirectory.setOnClickListener { loadDirectory(parentOf(currentPath)) }
        btnUpload.setOnClickListener {
            if (canStartTransfer()) chooseUpload.launch(arrayOf("*/*"))
        }
        btnCancelTransfer.setOnClickListener {
            isTransferRequestPending = false
            viewModel.cancelFileTransfer(args.sessionId)
            currentTransferState()?.let(::renderTransferState)
            updateActions()
        }
        renderBreadcrumbs(currentPath)
    }

    private fun observeSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    val session = state.sessions.firstOrNull { it.sessionId == args.sessionId }
                    if (session == null) {
                        pickerState.discardSession(args.sessionId)
                        if (!isLeavingFileTransfer) {
                            Toast.makeText(
                                requireContext(),
                                R.string.file_transfer_session_unavailable,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        leaveFileTransfer()
                        return@collectLatest
                    }

                    val wasConnected = isConnected
                    isConnected = session.connectionState is SSHConnectionState.Connected
                    if (!isConnected) {
                        isTransferRequestPending = false
                        cancelEntryOpen()
                    }
                    renderTransferState(session.fileTransferState)
                    updateActions()
                    if (!wasConnected && isConnected && currentEntries.isEmpty()) {
                        loadDirectory(currentPath)
                    }
                }
            }
        }
    }

    private fun loadDirectory(path: String) {
        cancelEntryOpen()
        if (!isConnected) {
            binding.swipeRefresh.isRefreshing = false
            showToast(R.string.file_transfer_connection_required)
            return
        }
        directoryJob?.cancel()
        directoryJob = viewLifecycleOwner.lifecycleScope.launch {
            renderDirectoryLoading(true)
            try {
                val listing = viewModel.listRemoteDirectory(args.sessionId, path)
                currentPath = listing.path
                pickerState.updateCurrentPath(args.sessionId, currentPath)
                val allEntries = listing.entries
                    .asSequence()
                    .filterNot { it.name == "." || it.name == ".." }
                    .sortedWith(
                        compareByDescending<RemoteFileEntry> { it.isDirectory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    )
                    .toList()
                currentEntries = allEntries.take(MAX_DIRECTORY_ENTRIES)
                fileAdapter.submitList(currentEntries)
                renderBreadcrumbs(currentPath)
                binding.tvEmpty.setText(R.string.file_transfer_empty)
                binding.tvEmpty.isVisible = currentEntries.isEmpty()
                if (allEntries.size > MAX_DIRECTORY_ENTRIES) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.file_transfer_directory_limited, MAX_DIRECTORY_ENTRIES),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Server/provider exceptions can contain remote absolute paths
                // or local content URIs. Keep them out of the UI.
                binding.tvEmpty.setText(R.string.file_transfer_load_failed)
                binding.tvEmpty.isVisible = true
            } finally {
                renderDirectoryLoading(false)
            }
        }
    }

    private fun cancelEntryOpen() {
        entryOpenGeneration++
        entryOpenJob?.cancel()
        entryOpenJob = null
    }

    private fun openEntry(entry: RemoteFileEntry) {
        cancelEntryOpen()
        if (!isConnected) {
            showToast(R.string.file_transfer_connection_required)
            return
        }
        if (!entry.isSymbolicLink) {
            openResolvedEntry(entry)
            return
        }
        // Resolve lazily, so large directory listings do not cause one stat per link.
        val generation = entryOpenGeneration
        entryOpenJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resolved = viewModel.resolveRemoteEntry(args.sessionId, entry.path)
                if (generation == entryOpenGeneration && isConnected &&
                    viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    openResolvedEntry(resolved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == entryOpenGeneration) {
                    Toast.makeText(
                        requireContext(),
                        requireContext().resolveMessage(
                            (e as? UserMessageCarrier)?.userMessage
                                ?: UserMessage(R.string.error_remote_link_unavailable)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openResolvedEntry(entry: RemoteFileEntry) {
        if (entry.isDirectory) {
            loadDirectory(entry.path)
            return
        }
        if (!canStartTransfer()) return
        pickerState.discardDownload(args.sessionId, pendingDownloadToken)
        pendingDownloadToken = pickerState.registerDownload(args.sessionId, entry)
        chooseDownloadDestination.launch(safeSuggestedName(entry.name))
    }

    private fun prepareUpload(uri: Uri) {
        if (!canStartTransfer()) return
        val displayName = queryDisplayName(uri)
        if (!isValidRemoteFileName(displayName)) {
            showToast(R.string.file_transfer_invalid_name)
            return
        }
        // Re-read the directory immediately before upload. The on-screen list
        // may be deliberately truncated or stale, but overwriting always needs
        // an explicit confirmation.
        val targetDirectory = currentPath
        uploadCheckJob?.cancel()
        uploadCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val listing = viewModel.listRemoteDirectory(args.sessionId, targetDirectory)
                val existing = listing.entries.firstOrNull { it.name == displayName }
                when {
                    existing == null -> startUpload(
                        uri,
                        listing.path,
                        displayName,
                        overwriteExisting = false
                    )
                    existing.isDirectory || existing.isSymbolicLink ->
                        showToast(R.string.file_transfer_invalid_name)
                    else -> AlertDialog.Builder(requireContext())
                        .setTitle(R.string.file_transfer_overwrite_title)
                        .setMessage(getString(
                            R.string.file_transfer_overwrite_message,
                            displayName.escapeRemoteText()
                        ))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.file_transfer_overwrite) { _, _ ->
                            startUpload(
                                uri,
                                listing.path,
                                displayName,
                                overwriteExisting = true
                            )
                        }
                        .create()
                        .also { dialog ->
                            overwriteDialog?.dismiss()
                            overwriteDialog = dialog
                            dialog.setOnDismissListener {
                                if (overwriteDialog === dialog) overwriteDialog = null
                            }
                            dialog.show()
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(
                    requireContext(),
                    R.string.file_transfer_overwrite_check_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startUpload(
        uri: Uri,
        remoteDirectory: String,
        displayName: String,
        overwriteExisting: Boolean
    ) {
        if (canStartTransfer()) {
            markTransferRequestPending()
            viewModel.uploadFile(
                args.sessionId,
                uri,
                remoteDirectory,
                displayName,
                overwriteExisting
            )
        }
    }

    private fun renderDirectoryLoading(loading: Boolean) {
        val b = _binding ?: return
        b.loadingIndicator.isVisible = loading && !b.swipeRefresh.isRefreshing
        b.swipeRefresh.isRefreshing = false
        b.btnRefresh.isEnabled = !loading && isConnected
        b.btnParentDirectory.isEnabled = !loading && isConnected && currentPath != "/"
        if (loading) b.tvEmpty.isVisible = false
    }

    private fun renderBreadcrumbs(path: String) {
        val b = _binding ?: return
        val container = b.breadcrumbContainer
        container.removeAllViews()
        val canonical = path.takeIf { it.startsWith('/') }
        if (canonical == null) {
            addBreadcrumb(path.escapeRemoteText(), path)
            return
        }

        addBreadcrumb("/", "/")
        var accumulated = ""
        canonical.split('/').filter(String::isNotEmpty).forEach { segment ->
            val separator = TextView(requireContext()).apply { text = "/" }
            container.addView(separator)
            accumulated += "/$segment"
            addBreadcrumb(segment.escapeRemoteText(), accumulated)
        }
        b.pathScroller.post { b.pathScroller.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun addBreadcrumb(label: String, path: String) {
        val button = MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(8.dp, 0, 8.dp, 0)
            setOnClickListener { loadDirectory(path) }
        }
        binding.breadcrumbContainer.addView(button)
    }

    private fun renderTransferState(state: FileTransferState) {
        val b = _binding ?: return
        val previous = lastTransferState
        if (state is FileTransferState.Running ||
            state is FileTransferState.Failed ||
            state is FileTransferState.Cancelled ||
            (state is FileTransferState.Completed && state != previous)) {
            isTransferRequestPending = false
        }
        lastTransferState = state
        isTransferRunning = state is FileTransferState.Running
        if (isTransferRequestPending && state is FileTransferState.Idle) {
            b.transferCard.isVisible = true
            b.btnCancelTransfer.isVisible = true
            b.tvTransferStatus.setText(R.string.file_transfer_preparing)
            b.transferProgress.isIndeterminate = true
            b.tvTransferProgress.text = ""
            return
        }
        b.transferCard.isVisible = state !is FileTransferState.Idle
        b.btnCancelTransfer.isVisible = isTransferRunning

        when (state) {
            FileTransferState.Idle -> Unit
            is FileTransferState.Running -> {
                b.tvTransferStatus.text = transferLabel(state.direction, state.remotePath)
                renderProgress(state.transferredBytes, state.totalBytes)
            }
            is FileTransferState.Completed -> {
                b.tvTransferStatus.setText(R.string.file_transfer_completed)
                renderProgress(
                    state.transferredBytes,
                    state.totalBytes ?: state.transferredBytes,
                    terminalState = true,
                    completed = true
                )
                if (previous != state && state.direction == FileTransferDirection.UPLOAD) {
                    loadDirectory(currentPath)
                }
            }
            is FileTransferState.Failed -> {
                // Failure strings can contain remote paths or provider URIs.
                b.tvTransferStatus.setText(R.string.file_transfer_failed)
                renderProgress(state.transferredBytes, state.totalBytes, terminalState = true)
            }
            is FileTransferState.Cancelled -> {
                b.tvTransferStatus.setText(R.string.file_transfer_cancelled)
                renderProgress(state.transferredBytes, state.totalBytes, terminalState = true)
            }
        }
    }

    private fun transferLabel(direction: FileTransferDirection, remotePath: String): String {
        val name = remotePath.substringAfterLast('/').ifBlank { remotePath }.escapeRemoteText()
        return getString(
            if (direction == FileTransferDirection.UPLOAD) R.string.file_transfer_uploading
            else R.string.file_transfer_downloading,
            name
        )
    }

    private fun renderProgress(
        transferred: Long,
        total: Long?,
        terminalState: Boolean = false,
        completed: Boolean = false
    ) = with(binding) {
        val safeTransferred = transferred.coerceAtLeast(0)
        val validTotal = total?.takeIf { it > 0 }
        transferProgress.isIndeterminate = validTotal == null && !terminalState
        if (validTotal != null) {
            transferProgress.max = PROGRESS_MAX
            transferProgress.progress = (
                safeTransferred.coerceAtMost(validTotal).toDouble() /
                    validTotal.toDouble() * PROGRESS_MAX
                ).toInt()
            tvTransferProgress.text = getString(
                R.string.file_transfer_progress_bytes,
                formatBytes(safeTransferred),
                formatBytes(validTotal)
            )
        } else {
            if (terminalState) {
                transferProgress.max = PROGRESS_MAX
                transferProgress.progress = if (completed) PROGRESS_MAX else 0
            }
            tvTransferProgress.text = getString(
                R.string.file_transfer_progress_transferred,
                formatBytes(safeTransferred)
            )
        }
    }

    private fun updateActions() {
        val b = _binding ?: return
        b.btnUpload.isEnabled = isConnected && !isTransferRunning && !isTransferRequestPending
        b.btnRefresh.isEnabled = isConnected
        b.btnParentDirectory.isEnabled = isConnected && currentPath != "/"
        b.fileList.alpha = if (isConnected) 1f else 0.55f
    }

    private fun canStartTransfer(): Boolean {
        val session = currentSession()
        if (session?.connectionState !is SSHConnectionState.Connected) {
            showToast(R.string.file_transfer_connection_required)
            return false
        }
        if (isTransferRequestPending || session.fileTransferState is FileTransferState.Running) {
            showToast(R.string.file_transfer_in_progress)
            return false
        }
        return true
    }

    private fun markTransferRequestPending() {
        isTransferRequestPending = true
        val b = _binding ?: return
        b.transferCard.isVisible = true
        b.tvTransferStatus.setText(R.string.file_transfer_preparing)
        b.transferProgress.isIndeterminate = true
        b.tvTransferProgress.text = ""
        b.btnCancelTransfer.isVisible = true
        updateActions()
    }

    private fun currentSession() = viewModel.uiState.value.sessions
        .firstOrNull { it.sessionId == args.sessionId }

    private fun currentTransferState(): FileTransferState? = currentSession()?.fileTransferState

    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            requireContext().contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    private fun safeSuggestedName(name: String): String {
        return name.toSafeDocumentName()
    }

    private fun isValidRemoteFileName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && !name.hasUnsafeRemoteDisplayCodePoint()

    /** Remove the unfilled document created by ACTION_CREATE_DOCUMENT. */
    private fun discardCreatedDownload(destination: Uri) {
        val activity = requireActivity()
        val applicationContext = activity.applicationContext
        val resolver = applicationContext.contentResolver
        activity.lifecycleScope.launch(Dispatchers.IO) {
            var removed = runCatching {
                DocumentsContract.deleteDocument(resolver, destination)
            }.getOrDefault(false)
            if (!removed) {
                removed = runCatching {
                    resolver.delete(destination, null, null) > 0
                }.getOrDefault(false)
            }
            if (!removed) {
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        applicationContext,
                        R.string.file_transfer_incomplete_document_cleanup_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun parentOf(path: String): String = when {
        path == "/" -> "/"
        !path.startsWith('/') -> ".."
        else -> path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "/")
            .ifEmpty { "/" }
    }

    private fun formatBytes(bytes: Long): String =
        android.text.format.Formatter.formatFileSize(requireContext(), bytes.coerceAtLeast(0))

    private fun showToast(stringRes: Int) {
        Toast.makeText(requireContext(), stringRes, Toast.LENGTH_SHORT).show()
    }

    /** Pop this destination at most once while its view is still active. */
    private fun leaveFileTransfer() {
        if (isLeavingFileTransfer ||
            !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            return
        }
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.fileTransferFragment) return
        isLeavingFileTransfer = true
        val navigated = runCatching { navController.navigateUp() }
            .onFailure {
                android.util.Log.w("FileTransferFragment", "Navigation was rejected")
            }
            .getOrDefault(false)
        if (!navigated) isLeavingFileTransfer = false
    }

    override fun onStop() {
        // A slow link lookup must not launch a document picker after Home/navigation.
        cancelEntryOpen()
        super.onStop()
    }

    override fun onDestroyView() {
        cancelEntryOpen()
        directoryJob?.cancel()
        directoryJob = null
        uploadCheckJob?.cancel()
        uploadCheckJob = null
        overwriteDialog?.dismiss()
        overwriteDialog = null
        binding.fileList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (isRemoving || requireActivity().isFinishing) {
            pickerState.discardDownload(args.sessionId, pendingDownloadToken)
            pendingDownloadToken = null
        }
        super.onDestroy()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val STATE_PENDING_DOWNLOAD_TOKEN = "pending_download_token"
        const val STATE_TRANSFER_REQUEST_PENDING = "transfer_request_pending"
        const val MAX_DIRECTORY_ENTRIES = 2_000
        const val PROGRESS_MAX = 10_000
    }
}
