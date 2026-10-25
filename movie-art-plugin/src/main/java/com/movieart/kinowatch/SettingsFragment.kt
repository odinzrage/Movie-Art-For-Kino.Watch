package com.movieart.kinowatch


import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import com.movieart.kinowatch.helpers.BackgroundPickerHelper
import com.movieart.kinowatch.helpers.KinoApi

class SettingsFragment : GuidedStepSupportFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        return Guidance(
            getString(R.string.plugin_name),
            getString(R.string.plugin_description),
            getString(R.string.settings),
            AppCompatResources.getDrawable(requireActivity(), R.drawable.ic_banner_drawable)
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        PreferencesManager.lastWallpaper = ""
        // Custom App Background Toggle
        val fallbackBackground = GuidedAction.Builder(context)
            .id(ACTION_ID_FALLBACK_BG_TOGGLE)
            .title(R.string.setting_fallback_bg_title)
            .description(
                getString(R.string.setting_fallback_bg_description,
                    PreferencesManager.fallbackBackground.text)
            )
            .descriptionEditable(false)
            .build()
        actions.add(fallbackBackground)

        // Brightness of the dynamic-colors gradient
        val dynamicBrightness = GuidedAction.Builder(context)
            .id(ACTION_ID_DYNAMIC_BRIGHTNESS)
            .title(R.string.setting_dynamic_brightness_title)
            .description(
                getString(R.string.setting_dynamic_brightness_description,
                    PreferencesManager.dynamicColorsBrightness)
            )
            .descriptionEditable(false)
            .build()
        actions.add(dynamicBrightness)

        // Pick from Gallery
        val actionPickGallery = GuidedAction.Builder(context)
            .id(ACTION_ID_PICK_GALLERY)
            .title(R.string.setting_fallback_bg_pick_gallery_title)
            .description(R.string.setting_fallback_bg_pick_gallery_description)
            .descriptionEditable(false)
            .build()
        actions.add(actionPickGallery)

        // Trailer in the background (on/off) + its last status
        val actionTrailer = GuidedAction.Builder(context)
            .id(ACTION_ID_TRAILER)
            .title(R.string.setting_trailer_title)
            .description(trailerDescription())
            .descriptionEditable(false)
            .multilineDescription(true)
            .build()
        actions.add(actionTrailer)

        // Kino.Watch account (needed once, so the plugin can look up trailers)
        val actionAccount = GuidedAction.Builder(context)
            .id(ACTION_ID_ACCOUNT)
            .title(accountTitle())
            .description(accountDescription())
            .descriptionEditable(false)
            .multilineDescription(true)
            .build()
        actions.add(actionAccount)
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_ID_FALLBACK_BG_TOGGLE -> {
                val newState: PreferencesManager.FallbackBackground = when (PreferencesManager.fallbackBackground) {
                    PreferencesManager.FallbackBackground.DynamicColors -> {
                        PreferencesManager.FallbackBackground.CustomBackground
                    }

                    else -> {
                        PreferencesManager.FallbackBackground.DynamicColors
                    }
                }
                PreferencesManager.fallbackBackground = newState
                updateToggleAction()
                println("Fallback background changed: $newState")
            }

            ACTION_ID_DYNAMIC_BRIGHTNESS -> {
                val steps = BRIGHTNESS_STEPS
                val current = PreferencesManager.dynamicColorsBrightness
                // next step that is lower than the current value, otherwise wrap around to the first
                val next = steps.firstOrNull { it < current } ?: steps.first()
                PreferencesManager.dynamicColorsBrightness = next
                val position = findActionPositionById(ACTION_ID_DYNAMIC_BRIGHTNESS)
                if (position >= 0) {
                    actions[position].description = getString(
                        R.string.setting_dynamic_brightness_description, next
                    )
                    notifyActionChanged(position)
                }
            }

            ACTION_ID_PICK_GALLERY -> {
                openGalleryPicker()
            }

            ACTION_ID_TRAILER -> {
                PreferencesManager.trailerEnabled = !PreferencesManager.trailerEnabled
                if (PreferencesManager.trailerEnabled && !KinoApi.isSignedIn(requireContext())) {
                    Toast.makeText(context, R.string.setting_trailer_need_login, Toast.LENGTH_LONG).show()
                }
                refreshTrailerAction()
            }

            ACTION_ID_ACCOUNT -> {
                val appContext = requireContext().applicationContext
                when {
                    loginInProgress -> {
                        // second press cancels a running sign-in
                        loginSession++
                        loginInProgress = false
                        loginMessage = null
                    }
                    KinoApi.isSignedIn(appContext) -> {
                        KinoApi.signOut(appContext)
                        loginMessage = null
                    }
                    else -> startLogin()
                }
                refreshAccountAction()
                refreshTrailerAction()
            }
        }
    }

    // ------------------------------------------------------------------ trailer / account

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var loginSession = 0
    private var loginInProgress = false
    private var loginMessage: String? = null

    private fun trailerDescription(): String {
        val state = if (PreferencesManager.trailerEnabled) "On" else "Off"
        return getString(
            R.string.setting_trailer_description,
            state,
            KinoApi.getStatus(requireContext())
        )
    }

    private fun accountTitle(): String {
        return getString(
            if (KinoApi.isSignedIn(requireContext())) R.string.setting_account_title_in
            else R.string.setting_account_title_out
        )
    }

    private fun accountDescription(): String {
        loginMessage?.let { return it }
        return getString(
            if (KinoApi.isSignedIn(requireContext())) R.string.setting_account_description_in
            else R.string.setting_account_description_out
        )
    }

    private fun refreshTrailerAction() {
        val position = findActionPositionById(ACTION_ID_TRAILER)
        if (position >= 0) {
            actions[position].description = trailerDescription()
            notifyActionChanged(position)
        }
    }

    private fun refreshAccountAction() {
        val position = findActionPositionById(ACTION_ID_ACCOUNT)
        if (position >= 0) {
            actions[position].title = accountTitle()
            actions[position].description = accountDescription()
            notifyActionChanged(position)
        }
    }

    /** Shows [message] in the account item, but only if this sign-in attempt is still the current one. */
    private fun postAccountMessage(session: Int, message: String?, finished: Boolean) {
        mainHandler.post {
            if (session != loginSession || !isAdded) return@post
            loginMessage = message
            if (finished) loginInProgress = false
            refreshAccountAction()
            if (finished) refreshTrailerAction()
        }
    }

    private fun startLogin() {
        val appContext = requireContext().applicationContext
        loginSession++
        val session = loginSession
        loginInProgress = true
        loginMessage = getString(R.string.setting_account_connecting)

        Thread {
            val (device, error) = KinoApi.startDeviceLogin(appContext)
            if (session != loginSession) return@Thread
            if (device == null) {
                postAccountMessage(session, "Could not start sign-in: $error", true)
                return@Thread
            }
            postAccountMessage(
                session,
                "On your phone or PC open ${device.verificationUri}\nand enter the code:  ${device.userCode}\nWaiting for confirmation ... (press again to cancel)",
                false
            )
            val deadline = System.currentTimeMillis() + device.expiresInSec * 1000L
            val intervalMs = maxOf(device.intervalSec, 3) * 1000L
            while (System.currentTimeMillis() < deadline && session == loginSession) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (session != loginSession) return@Thread
                val result = KinoApi.pollDeviceLogin(appContext, device.code)
                when (result.state) {
                    KinoApi.PollState.SIGNED_IN -> {
                        postAccountMessage(session, null, true)
                        return@Thread
                    }
                    KinoApi.PollState.FAILED -> {
                        postAccountMessage(session, "Sign-in failed: ${result.message}", true)
                        return@Thread
                    }
                    KinoApi.PollState.PENDING -> { }
                }
            }
            if (session == loginSession) {
                postAccountMessage(session, "The code expired. Press to try again.", true)
            }
        }.start()
    }

    override fun onDestroy() {
        loginSession++ // stop a running sign-in when the screen is closed
        super.onDestroy()
    }

    private fun openGalleryPicker() {
        // Not every Android TV firmware ships the system document picker (ACTION_OPEN_DOCUMENT) -
        // on those boxes the system just says "action is not supported by any app". File managers
        // such as Solid Explorer answer ACTION_GET_CONTENT instead, so try the options in order.
        val mimeTypes = arrayOf("image/*", "video/*")
        val candidates = listOf(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            },
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            },
            Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        )
        val pm = requireContext().packageManager
        for (intent in candidates) {
            val handlers = try {
                pm.queryIntentActivities(intent, 0)
            } catch (_: Exception) {
                emptyList()
            }
            if (handlers.isEmpty()) continue
            // Several apps can answer (gallery, Solid Explorer, ...): let the user choose.
            val toLaunch = if (handlers.size > 1) {
                Intent.createChooser(intent, getString(R.string.setting_fallback_bg_pick_gallery_title))
            } else {
                intent
            }
            try {
                startActivityForResult(toLaunch, REQUEST_CODE_PICK_IMAGE)
                return
            } catch (_: ActivityNotFoundException) {
                // try the next kind of picker
            } catch (_: SecurityException) {
                // try the next kind of picker
            }
        }
        // Package visibility may hide handlers on some systems: still try the most common request.
        try {
            startActivityForResult(candidates[0], REQUEST_CODE_PICK_IMAGE)
            return
        } catch (_: Exception) {
            // fall through to the message below
        }
        Toast.makeText(context, R.string.setting_pick_no_app, Toast.LENGTH_LONG).show()
    }

    private fun updateToggleAction() {
        val position = findActionPositionById(ACTION_ID_FALLBACK_BG_TOGGLE)
        if (position >= 0) {
            val action = actions[position]
            action.description = getString(
                R.string.setting_fallback_bg_description,
                PreferencesManager.fallbackBackground.text
            )
            notifyActionChanged(position)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val pickedUri = data?.data
                ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            pickedUri?.let { uri ->
                val context = requireContext()
                val file = BackgroundPickerHelper.copyBackgroundFromUri(context, uri)
                if (file != null && file.exists()) {
                    // Delete old file if it exists to avoid cluttering cache
                    PreferencesManager.customFallbackBackgroundName?.let { oldName ->
                        val oldFile = BackgroundPickerHelper.getCustomBackgroundFile(context, oldName)
                        if (oldFile.exists() && oldFile.name != file.name) {
                            oldFile.delete()
                        }
                    }
                    
                    PreferencesManager.customFallbackBackgroundName = file.name
                    PreferencesManager.fallbackBackground = PreferencesManager.FallbackBackground.CustomBackground
                    updateToggleAction()
                    Toast.makeText(context, R.string.fallback_bg_set_success, Toast.LENGTH_LONG).show()
                    println("Custom background set from gallery: ${file.absolutePath}")
                } else {
                    Toast.makeText(context, "Could not read the selected file. Try another file or file manager.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private const val ACTION_ID_FALLBACK_BG_TOGGLE = 3L
        private const val ACTION_ID_PICK_GALLERY = 4L
        private const val ACTION_ID_DYNAMIC_BRIGHTNESS = 5L
        private const val ACTION_ID_TRAILER = 6L
        private const val ACTION_ID_ACCOUNT = 7L
        private val BRIGHTNESS_STEPS = listOf(100, 75, 60, 45, 30)

        private const val REQUEST_CODE_PICK_IMAGE = 100
    }
}
