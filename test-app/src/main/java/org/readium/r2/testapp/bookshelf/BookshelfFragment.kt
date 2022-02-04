/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.bookshelf

import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.shared.extensions.tryOrLog
import org.readium.r2.shared.publication.Locator
import org.readium.r2.lingVisSdk.LingVisSDK
import org.readium.r2.testapp.R
import org.readium.r2.testapp.databinding.FragmentBookshelfBinding
import org.readium.r2.testapp.domain.model.Book
import org.readium.r2.testapp.opds.GridAutoFitLayoutManager
import org.readium.r2.testapp.reader.ReaderContract
import kotlin.Result.Companion.failure

class BookshelfFragment : Fragment() {

    private val bookshelfViewModel: BookshelfViewModel by viewModels()
    private lateinit var bookshelfAdapter: BookshelfAdapter
    private lateinit var documentPickerLauncher: ActivityResultLauncher<String>
    private lateinit var readerLauncher: ActivityResultLauncher<ReaderContract.Input>
    private var _binding: FragmentBookshelfBinding? = null
    private val binding get() = _binding!!
    private lateinit var lingVisSdk: LingVisSDK
    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        bookshelfViewModel.channel.receive(this) { handleEvent(it) }
        _binding = FragmentBookshelfBinding.inflate(
            inflater, container, false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bookshelfAdapter = BookshelfAdapter(onBookClick = { book -> openBook(book.id) },
            onBookLongClick = { book -> confirmDeleteBook(book) })

        documentPickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let {
                    binding.bookshelfProgressBar.visibility = View.VISIBLE
                    bookshelfViewModel.importPublicationFromUri(it)
                }
            }

        readerLauncher =
            registerForActivityResult(ReaderContract()) { pubData: ReaderContract.Output? ->
                tryOrLog { pubData?.publication?.close() }
            }

        binding.bookshelfBookList.apply {
            setHasFixedSize(true)
            layoutManager = GridAutoFitLayoutManager(requireContext(), 120)
            adapter = bookshelfAdapter
            addItemDecoration(
                VerticalSpaceItemDecoration(
                    10
                )
            )
        }

        bookshelfViewModel.books.observe(viewLifecycleOwner, {
            bookshelfAdapter.submitList(it)
        })

        lingVisSdk = LingVisSDK(null, requireActivity().applicationContext, null)

        // FIXME embedded dialogs like this are ugly
        binding.bookshelfAddBookFab.setOnClickListener {
            var selected = 0
            val showResult = { result: Result<String>, successMsg: String? ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if (result.isFailure) "Error" else "")
                    .setMessage(if (result.isFailure) result.exceptionOrNull()?.message else (successMsg ?: result.getOrNull()))
                    .setPositiveButton(getString(R.string.ok), null).show()
            }
            val showSettings = {
                uiScope.launch {
                    val result = lingVisSdk.getSettings()
                    var msg = result.getOrNull()
                    if (msg != null) {
                        val parts = msg.split(",")
                        msg =
                            "Learning language: ${parts[0]}\nYour language: ${parts[1]}\nYour level: ${parts[2]}\nEmail: ${parts[3]}"
                    }
                    showResult(result, msg)
                }
            }
            MaterialAlertDialogBuilder(requireContext())
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.cancel()
                }
                .setPositiveButton(getString(R.string.ok)) { _, _ ->
                    if (selected == 0) {
                        documentPickerLauncher.launch("*/*")
                    } else if (selected == 1) {
                        val urlEditText = EditText(requireContext())
                        val urlDialog = MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.add_book))
                            .setMessage(R.string.enter_url)
                            .setView(urlEditText)
                            .setNegativeButton(R.string.cancel) { dialog, _ ->
                                dialog.cancel()
                            }
                            .setPositiveButton(getString(R.string.ok), null)
                            .show()
                        urlDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if (TextUtils.isEmpty(urlEditText.text)) {
                                urlEditText.error = getString(R.string.invalid_url)
                            } else if (!URLUtil.isValidUrl(urlEditText.text.toString())) {
                                urlEditText.error = getString(R.string.invalid_url)
                            } else {
                                val url = urlEditText.text.toString()
                                val uri = Uri.parse(url)
                                binding.bookshelfProgressBar.visibility = View.VISIBLE
                                bookshelfViewModel.importPublicationFromUri(uri, url)
                                urlDialog.dismiss()
                            }
                        }
                    } else if (selected == 2 || selected == 3) {
                        val dialogView: View = LayoutInflater.from(activity).inflate(R.layout.sign_in_dialog, null, false)
                        MaterialAlertDialogBuilder(requireContext())
                            .setView(dialogView)
                            .setMessage(if (selected == 2) "Sign In\nUse an existing account" else "Sign Up\nCreate a new account")
                            .setNegativeButton(R.string.cancel) { dialog, _ ->
                                dialog.cancel()
                            }
                            .setPositiveButton(if (selected == 2) "Sign In" else "Sign Up") { dialog, _ ->
                                val emailField: EditText = dialogView.findViewById(R.id.emailEdit)
                                val passwordField: EditText = dialogView.findViewById(R.id.passwordEdit)
                                val email: String = emailField.text.toString().trim()
                                val password: String = passwordField.text.toString().trim()
                                if (email == "") {
                                    showResult(failure(Exception("Email and password cannot be empty")), "")
                                } else {
                                    uiScope.launch {
                                        showResult(lingVisSdk.signIn(email, password, selected == 3), "Signed in as ${email}")
                                    }
                                }
                                dialog.dismiss()
                            }
                            .show()
                    } else if (selected == 4) {
                        showSettings()
                    } else if (selected == 5) {
                        val dialogView: View = LayoutInflater.from(activity).inflate(R.layout.update_settings_dialog, null, false)
                        MaterialAlertDialogBuilder(requireContext())
                            .setView(dialogView)
                            .setMessage("Change any of the settings below, leave empty those you don't want to change")
                            .setNegativeButton(R.string.cancel) { dialog, _ ->
                                dialog.cancel()
                            }
                            .setPositiveButton("Update") { dialog, _ ->
                                val l1Field: EditText = dialogView.findViewById(R.id.l1Edit)
                                val l2Field: EditText = dialogView.findViewById(R.id.l2Edit)
                                val levelField: EditText = dialogView.findViewById(R.id.levelEdit)
                                val l1: String = l1Field.text.toString().trim()
                                val l2: String = l2Field.text.toString().trim()
                                val level: String = levelField.text.toString().trim()
                                uiScope.launch {
                                    val result = lingVisSdk.updateSettings(l2, l1, level)
                                    if (result.isFailure) {
                                        showResult(result, null)
                                    } else {
                                        showSettings()
                                    }
                                }
                                dialog.dismiss()
                            }
                            .show()
                    }
                }
                .setSingleChoiceItems(R.array.documentSelectorArray, 0) { _, which ->
                    selected = which
                }
                .show()
        }
    }

    private fun handleEvent(event: BookshelfViewModel.Event) {
        val message =
            when (event) {
                is BookshelfViewModel.Event.ImportPublicationFailed -> {
                    "Error: " + event.errorMessage
                }
                is BookshelfViewModel.Event.UnableToMovePublication -> getString(R.string.unable_to_move_pub)
                is BookshelfViewModel.Event.ImportPublicationSuccess -> getString(R.string.import_publication_success)
                is BookshelfViewModel.Event.ImportDatabaseFailed -> getString(R.string.unable_add_pub_database)
                is BookshelfViewModel.Event.OpenBookError -> {
                    "Error: " + event.errorMessage
                }
            }
        binding.bookshelfProgressBar.visibility = View.GONE
        Snackbar.make(
            requireView(),
            message,
            Snackbar.LENGTH_LONG
        ).show()
    }

    class VerticalSpaceItemDecoration(private val verticalSpaceHeight: Int) :
        RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.bottom = verticalSpaceHeight
        }
    }

    private fun deleteBook(book: Book) {
        bookshelfViewModel.deleteBook(book)
    }

    private fun openBook(bookId: Long?) {
        bookId ?: return

        bookshelfViewModel.openBook(requireContext(), bookId) { book, asset, publication, url ->
            readerLauncher.launch(ReaderContract.Input(
                mediaType = asset.mediaType(),
                publication = publication,
                bookId = bookId,
                initialLocator = book.progression?.let { Locator.fromJSON(JSONObject(it)) },
                baseUrl = url
            ))
        }
    }

    private fun confirmDeleteBook(book: Book) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_delete_book_title))
            .setMessage(getString(R.string.confirm_delete_book_text))
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .setPositiveButton(getString(R.string.delete)) { dialog, _ ->
                deleteBook(book)
                dialog.dismiss()
            }
            .show()
    }
}
