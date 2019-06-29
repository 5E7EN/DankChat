package com.flxrs.dankchat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.forEach
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.viewpager2.widget.ViewPager2
import com.flxrs.dankchat.chat.ChatTabAdapter
import com.flxrs.dankchat.databinding.MainActivityBinding
import com.flxrs.dankchat.preferences.DankChatPreferenceStore
import com.flxrs.dankchat.preferences.MentionTemplate
import com.flxrs.dankchat.service.api.TwitchApi
import com.flxrs.dankchat.utils.MediaUtils
import com.flxrs.dankchat.utils.dialog.AddChannelDialogResultHandler
import com.flxrs.dankchat.utils.dialog.AdvancedLoginDialogResultHandler
import com.flxrs.dankchat.utils.dialog.EditTextDialogFragment
import com.flxrs.dankchat.utils.reduceDragSensitivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.io.IOException


class MainActivity : AppCompatActivity(), AddChannelDialogResultHandler, AdvancedLoginDialogResultHandler {
	private val viewModel: DankChatViewModel by viewModel()
	private val channels = mutableListOf<String>()
	private lateinit var preferenceStore: DankChatPreferenceStore
	private lateinit var binding: MainActivityBinding
	private lateinit var adapter: ChatTabAdapter
	private lateinit var tabLayoutMediator: TabLayoutMediator
	private var currentImagePath = ""
	private var showProgressBar = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		preferenceStore = DankChatPreferenceStore(this)
		val oauth = preferenceStore.getOAuthKey() ?: ""
		val name = preferenceStore.getUserName() ?: ""
		val id = preferenceStore.getUserId()

		adapter = ChatTabAdapter(supportFragmentManager, lifecycle)
		preferenceStore.getChannels()?.run { channels.addAll(this) }
		channels.forEach { adapter.addFragment(it) }

		binding = DataBindingUtil.setContentView<MainActivityBinding>(this, R.layout.main_activity).apply {
			viewPager.adapter = adapter
			viewPager.reduceDragSensitivity()
			viewPager.offscreenPageLimit = if (channels.size > 1) channels.size - 1 else ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
			tabLayoutMediator = TabLayoutMediator(tabs, viewPager) { tab, position -> tab.text = adapter.titleList[position] }
			tabLayoutMediator.attach()
			tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
				override fun onTabReselected(tab: TabLayout.Tab?) = Unit

				override fun onTabSelected(tab: TabLayout.Tab?) = Unit

				override fun onTabUnselected(tab: TabLayout.Tab?) {
					val position = tab?.position ?: -1
					if (position in 0 until adapter.fragmentList.size) {
						adapter.fragmentList[position].clearInputFocus()
					}
				}
			})
			showActionbarFab.setOnClickListener { showActionBar() }
		}

		viewModel.imageUploadedEvent.observe(this, Observer { (urlOrError, file) ->
			val message: String = if (!urlOrError.startsWith("Error")) {
				val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
				clipboard.primaryClip = android.content.ClipData.newPlainText("nuuls image url", urlOrError)
				"Copied: $urlOrError"
			} else urlOrError

			showSnackbar(message)
			showProgressBar = false
			invalidateOptionsMenu()
			file.delete()
		})

		setSupportActionBar(binding.toolbar)
		updateViewPagerVisibility()

		if (savedInstanceState == null) {
			if (name.isNotBlank() && oauth.isNotBlank()) showSnackbar("Logged in as $name")
			connectAndJoinChannels(name, oauth, id, true)
		}
	}

	override fun onPause() {
		val position = binding.tabs.selectedTabPosition
		if (position in 0 until adapter.fragmentList.size) {
			adapter.fragmentList[position].clearInputFocus()
		}
		super.onPause()
	}

	override fun onResume() {
		super.onResume()
		reconnect(true)
	}

	override fun onBackPressed() {
		moveTaskToBack(true)
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.menu, menu)
		return true
	}

	override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
		menu?.run {
			val isLoggedIn = preferenceStore.isLoggedin()
			findItem(R.id.menu_login)?.isVisible = !isLoggedIn
			findItem(R.id.menu_logout)?.isVisible = isLoggedIn
			findItem(R.id.menu_remove)?.isVisible = channels.isNotEmpty()

			findItem(R.id.progress)?.run {
				isVisible = showProgressBar
				actionView = ProgressBar(this@MainActivity).apply {
					indeterminateTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.white)
					isVisible = showProgressBar
				}
			}
			findItem(R.id.menu_change_mention)?.subMenu?.run {
				forEach { it.isChecked = false }
				when (preferenceStore.getMentionTemplate()) {
					MentionTemplate.DEFAULT.value           -> this[0].isChecked = true
					MentionTemplate.WITH_AT.value           -> this[1].isChecked = true
					MentionTemplate.WITH_AT_AND_COMMA.value -> this[2].isChecked = true
				}
			}
		}
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.menu_reconnect                 -> reconnect(false)
			R.id.menu_login_default             -> Intent(this, LoginActivity::class.java).run { startActivityForResult(this, LOGIN_REQUEST) }
			R.id.menu_login_advanced            -> showAdvancedLoginDialog()
			R.id.menu_logout                    -> showLogoutConfirmationDialog()
			R.id.menu_add                       -> addChannel()
			R.id.menu_remove                    -> removeChannel()
			R.id.menu_clear                     -> clear()
			R.id.menu_reload_emotes             -> reloadEmotes()
			R.id.menu_choose_image              -> checkPermissionForGallery()
			R.id.menu_capture_image             -> startCameraCapture()
			R.id.menu_hide                      -> hideActionBar()
			R.id.menu_mention_default           -> setMentionTemplate(MentionTemplate.DEFAULT)
			R.id.menu_mention_with_at           -> setMentionTemplate(MentionTemplate.WITH_AT)
			R.id.menu_mention_with_at_and_comma -> setMentionTemplate(MentionTemplate.WITH_AT_AND_COMMA)
			else                                -> return false
		}
		return true
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		when (requestCode) {
			LOGIN_REQUEST   -> {
				val oauth = preferenceStore.getOAuthKey()
				val name = preferenceStore.getUserName()
				val id = preferenceStore.getUserId()

				if (resultCode == Activity.RESULT_OK && !oauth.isNullOrBlank() && !name.isNullOrBlank() && id != 0) {
					viewModel.close { connectAndJoinChannels(name, oauth, id) }

					preferenceStore.setLoggedIn(true)
					showSnackbar("Logged in as $name")
				} else {
					showSnackbar("Failed to login")
				}
			}
			GALLERY_REQUEST -> {
				if (resultCode == Activity.RESULT_OK) {
					val uri = data?.data
					val original = File(MediaUtils.getImagePathFromUri(this, uri) ?: return)
					val copy = MediaUtils.createImageFile(this, original.extension)
					try {
						original.copyTo(copy, true)
						if (copy.extension == "jpg" || copy.extension == "jpeg") MediaUtils.removeExifAttributes(copy.absolutePath)

						viewModel.uploadImage(copy)
						showProgressBar = true
						invalidateOptionsMenu()
					} catch (t: Throwable) {
						copy.delete()
						showSnackbar("Error during upload")
					}
				}
			}
			CAPTURE_REQUEST -> {
				if (resultCode == Activity.RESULT_OK) {
					val imageFile = File(currentImagePath)
					try {
						MediaUtils.removeExifAttributes(currentImagePath)
						viewModel.uploadImage(imageFile)
						showProgressBar = true
						invalidateOptionsMenu()
					} catch (e: IOException) {
						imageFile.delete()
						showSnackbar("Error during upload")
					}
				}
			}
		}
		super.onActivityResult(requestCode, resultCode, data)
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		if (requestCode == GALLERY_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			startGalleryPicker()
		}
	}

	override fun onAddChannelDialogResult(channel: String) {
		val lowerCaseChannel = channel.toLowerCase()
		if (!channels.contains(lowerCaseChannel)) {
			val oauth = preferenceStore.getOAuthKey() ?: ""
			val name = preferenceStore.getUserName() ?: ""
			val id = preferenceStore.getUserId()
			viewModel.connectOrJoinChannel(lowerCaseChannel, name, oauth, id, true)
			channels.add(lowerCaseChannel)
			preferenceStore.setChannels(channels.toMutableSet())

			adapter.addFragment(lowerCaseChannel)
			binding.viewPager.setCurrentItem(channels.size - 1, true)
			binding.viewPager.offscreenPageLimit = if (channels.size > 1) channels.size - 1 else ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT

			invalidateOptionsMenu()
			updateViewPagerVisibility()
		}
	}

	override fun onAdvancedLoginDialogResult(token: String) {
		val tokenWithoutSuffix = when {
			token.startsWith("oauth:", true) -> token.substringAfter(':')
			else                             -> token
		}

		CoroutineScope(Dispatchers.IO).launch {
			TwitchApi.getUser(tokenWithoutSuffix)?.let {
				if (it.name.isNotBlank()) {
					preferenceStore.apply {
						setOAuthKey("oauth:$tokenWithoutSuffix")
						setUserName(it.name.toLowerCase())
						setUserId(it.id)
						setLoggedIn(true)
					}
					viewModel.close { connectAndJoinChannels(it.name, "oauth:$tokenWithoutSuffix", it.id) }
					showSnackbar("Logged in as ${it.name}")
				} else showSnackbar("Failed to login")
			} ?: showSnackbar("Invalid OAuth token")
		}
	}

	private fun setMentionTemplate(template: MentionTemplate) {
		preferenceStore.setMentionTemplate(template)
		invalidateOptionsMenu()
	}

	private fun checkPermissionForGallery() {
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), GALLERY_REQUEST)
		} else {
			startGalleryPicker()
		}
	}

	private fun startCameraCapture() {
		Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { captureIntent ->
			captureIntent.resolveActivity(packageManager)?.also {
				try {
					MediaUtils.createImageFile(this).apply { currentImagePath = absolutePath }
				} catch (ex: IOException) {
					null
				}?.also {
					val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
					captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
					startActivityForResult(captureIntent, CAPTURE_REQUEST)
				}
			}
		}
	}

	private fun startGalleryPicker() {
		Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).also { galleryIntent ->
			galleryIntent.resolveActivity(packageManager)?.also {
				startActivityForResult(galleryIntent, GALLERY_REQUEST)
			}
		}
	}

	private fun hideActionBar() {
		supportActionBar?.hide()
		binding.tabs.visibility = View.GONE
		binding.showActionbarFab.visibility = View.VISIBLE
	}

	private fun showActionBar() {
		supportActionBar?.show()
		binding.showActionbarFab.visibility = View.GONE
		binding.tabs.visibility = View.VISIBLE
	}

	private fun clear() {
		val position = binding.tabs.selectedTabPosition
		if (position in 0 until adapter.titleList.size) {
			viewModel.clear(adapter.titleList[position])
		}
	}

	private fun reloadEmotes() {
		val position = binding.tabs.selectedTabPosition
		if (position in 0 until adapter.titleList.size) {
			val oauth = preferenceStore.getOAuthKey() ?: ""
			val userId = preferenceStore.getUserId()
			viewModel.reloadEmotes(adapter.titleList[position], oauth, userId)
		}
	}

	private fun reconnect(onlyIfNecessary: Boolean = false) {
		viewModel.reconnect(onlyIfNecessary)
	}

	private fun connectAndJoinChannels(name: String, oauth: String, id: Int, load3rdPartyEmotesAndBadges: Boolean = false) {
		if (channels.isEmpty()) {
			viewModel.connectOrJoinChannel("", name, oauth, id, false, doReauth = true)
		} else channels.forEachIndexed { i, channel ->
			viewModel.connectOrJoinChannel(channel, name, oauth, id, load3rdPartyEmotesAndBadges, i == 0)
		}
	}

	private fun updateViewPagerVisibility() = with(binding) {
		if (channels.size > 0) {
			viewPager.visibility = View.VISIBLE
			tabs.visibility = View.VISIBLE
			addChannelsText.visibility = View.GONE
		} else {
			viewPager.visibility = View.GONE
			tabs.visibility = View.GONE
			addChannelsText.visibility = View.VISIBLE
		}
	}

	private fun showSnackbar(message: String) {
		Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).apply {
			view.setBackgroundResource(R.color.colorPrimary)
			setTextColor(Color.WHITE)
		}.show()
	}

	private fun showLogoutConfirmationDialog() = MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.confirm_logout_title))
			.setMessage(getString(R.string.confirm_logout_message))
			.setPositiveButton(getString(R.string.confirm_logout_positive_button)) { dialog, _ ->
				viewModel.close { connectAndJoinChannels("", "", 0) }
				preferenceStore.setUserName("")
				preferenceStore.setOAuthKey("")
				preferenceStore.setUserId(0)
				preferenceStore.setLoggedIn(false)
				dialog.dismiss()
			}
			.setNegativeButton(getString(R.string.confirm_logout_negative_button)) { dialog, _ -> dialog.dismiss() }
			.create().show()

	private fun showAdvancedLoginDialog() = EditTextDialogFragment.create(
			R.string.login_with_oauth,
			R.string.confirm_logout_negative_button,
			R.string.login,
			R.string.required_oauth_scopes,
			"Token",
			false
	).show(supportFragmentManager, DIALOG_TAG)

	private fun addChannel() = EditTextDialogFragment.create(
			R.string.dialog_title,
			R.string.dialog_negative_button,
			R.string.dialog_positive_button,
			textHint = "Channel",
			isAddChannel = true
	).show(supportFragmentManager, DIALOG_TAG)

	private fun removeChannel() {
		val index = binding.viewPager.currentItem
		val channel = channels[index]
		channels.remove(channel)
		preferenceStore.setChannels(channels.toMutableSet())
		viewModel.partChannel(channel)
		if (channels.size > 0) {
			binding.viewPager.setCurrentItem(0, true)
		}

		binding.viewPager.offscreenPageLimit = if (channels.size > 1) channels.size - 1 else ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
		adapter.removeFragment(index)

		invalidateOptionsMenu()
		updateViewPagerVisibility()
	}

	companion object {
		private val TAG = MainActivity::class.java.simpleName
		private const val DIALOG_TAG = "add_channel_dialog"
		private const val LOGIN_REQUEST = 42
		private const val GALLERY_REQUEST = 69
		private const val CAPTURE_REQUEST = 420
	}
}
