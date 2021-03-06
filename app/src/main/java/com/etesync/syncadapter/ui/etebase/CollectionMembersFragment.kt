package com.etesync.syncadapter.ui.etebase

import android.app.Dialog
import android.app.ProgressDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.etebase.client.CollectionAccessLevel
import com.etebase.client.Utils
import com.etebase.client.exceptions.EtebaseException
import com.etebase.client.exceptions.NotFoundException
import com.etesync.syncadapter.CachedCollection
import com.etesync.syncadapter.Constants
import com.etesync.syncadapter.R
import com.etesync.syncadapter.resource.LocalCalendar
import com.etesync.syncadapter.syncadapter.requestSync
import com.etesync.syncadapter.ui.BaseActivity
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread

class CollectionMembersFragment : Fragment() {
    private val model: AccountViewModel by activityViewModels()
    private val collectionModel: CollectionViewModel by activityViewModels()
    private var isAdmin: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val ret = if (collectionModel.value!!.col.accessLevel == CollectionAccessLevel.Admin) {
            isAdmin = true
            inflater.inflate(R.layout.etebase_view_collection_members, container, false)
        } else {
            inflater.inflate(R.layout.etebase_view_collection_members_no_access, container, false)
        }

        if (savedInstanceState == null) {
            collectionModel.observe(this) {
                (activity as? BaseActivity?)?.supportActionBar?.setTitle(R.string.collection_members_title)
                if (container != null) {
                    initUi(inflater, ret, it)
                }
            }
        }

        return ret
    }

    private fun initUi(inflater: LayoutInflater, v: View, cachedCollection: CachedCollection) {
        val meta = cachedCollection.meta
        val collectionType = cachedCollection.collectionType
        val colorSquare = v.findViewById<View>(R.id.color)
        val color = LocalCalendar.parseColor(meta.color)
        when (collectionType) {
            Constants.ETEBASE_TYPE_CALENDAR -> {
                colorSquare.setBackgroundColor(color)
            }
            Constants.ETEBASE_TYPE_TASKS -> {
                colorSquare.setBackgroundColor(color)
            }
            Constants.ETEBASE_TYPE_ADDRESS_BOOK -> {
                colorSquare.visibility = View.GONE
            }
        }

        val title = v.findViewById<View>(R.id.display_name) as TextView
        title.text = meta.name

        val desc = v.findViewById<View>(R.id.description) as TextView
        desc.text = meta.description

        if (isAdmin) {
            v.findViewById<View>(R.id.add_member).setOnClickListener {
                addMemberClicked()
            }
        } else {
            v.findViewById<Button>(R.id.leave).setOnClickListener {
                doAsync {
                    val membersManager = model.value!!.colMgr.getMemberManager(cachedCollection.col)
                    membersManager.leave()
                    val applicationContext = activity?.applicationContext
                    if (applicationContext != null) {
                        requestSync(applicationContext, model.value!!.account)
                    }
                    activity?.finish()
                }
            }
        }

        v.findViewById<View>(R.id.progressBar).visibility = View.GONE
    }

    private fun addMemberClicked() {
        val view = View.inflate(requireContext(), R.layout.add_member_fragment, null)
        val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.collection_members_add)
                .setIcon(R.drawable.ic_account_add_dark)
                .setPositiveButton(android.R.string.yes) { _, _ ->
                    val username = view.findViewById<EditText>(R.id.username).text.toString()
                    val readOnly = view.findViewById<CheckBox>(R.id.read_only).isChecked

                    val frag = AddMemberFragment.newInstance(model.value!!, collectionModel.value!!, username, if (readOnly) CollectionAccessLevel.ReadOnly else CollectionAccessLevel.ReadWrite)
                    frag.show(childFragmentManager, null)
                }
                .setNegativeButton(android.R.string.no) { _, _ -> }
        dialog.setView(view)
        dialog.show()
    }
}

class AddMemberFragment : DialogFragment() {
    private lateinit var accountHolder: AccountHolder
    private lateinit var cachedCollection: CachedCollection
    private lateinit var username: String
    private lateinit var accessLevel: CollectionAccessLevel

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val progress = ProgressDialog(context)
        progress.setTitle(R.string.collection_members_adding)
        progress.setMessage(getString(R.string.please_wait))
        progress.isIndeterminate = true
        progress.setCanceledOnTouchOutside(false)
        isCancelable = false

        doAsync {
            val invitationManager = accountHolder.etebase.invitationManager
            try {
                val profile = invitationManager.fetchUserProfile(username)
                val fingerprint = Utils.prettyFingerprint(profile.pubkey)
                uiThread {
                    val view = LayoutInflater.from(context).inflate(R.layout.fingerprint_alertdialog, null)
                    (view.findViewById<View>(R.id.body) as TextView).text = getString(R.string.trust_fingerprint_body, username)
                    (view.findViewById<View>(R.id.fingerprint) as TextView).text = fingerprint
                    AlertDialog.Builder(requireContext())
                            .setIcon(R.drawable.ic_fingerprint_dark)
                            .setTitle(R.string.trust_fingerprint_title)
                            .setView(view)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                doAsync {
                                    try {
                                        invitationManager.invite(cachedCollection.col, username, profile.pubkey, accessLevel)
                                        uiThread {
                                            AlertDialog.Builder(requireContext())
                                                .setTitle(R.string.collection_members_add)
                                                .setIcon(R.drawable.ic_account_add_dark)
                                                .setMessage(R.string.collection_members_add_success)
                                                .setPositiveButton(android.R.string.yes) { _, _ -> }
                                                .show()
                                            dismiss()
                                        }
                                    } catch (e: EtebaseException) {
                                        uiThread { handleError(e.localizedMessage) }
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> dismiss() }.show()
                }
            } catch (e: NotFoundException) {
                uiThread { handleError(getString(R.string.collection_members_error_user_not_found, username)) }
            } catch (e: EtebaseException) {
                uiThread { handleError(e.localizedMessage) }
            }
        }

        return progress
    }

    private fun handleError(message: String) {
        AlertDialog.Builder(requireContext())
                .setIcon(R.drawable.ic_error_dark)
                .setTitle(R.string.collection_members_add_error)
                .setMessage(message)
                .setPositiveButton(android.R.string.yes) { _, _ -> }.show()
        dismiss()
    }

    companion object {
        fun newInstance(accountHolder: AccountHolder, cachedCollection: CachedCollection,
                        username: String, accessLevel: CollectionAccessLevel): AddMemberFragment {
            val ret = AddMemberFragment()
            ret.accountHolder = accountHolder
            ret.cachedCollection = cachedCollection
            ret.username = username
            ret.accessLevel = accessLevel
            return ret
        }
    }
}