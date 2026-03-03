package ru.acestar.vpn.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import ru.acestar.vpn.R
import ru.acestar.vpn.databinding.ActivityLegalDocumentBinding

class LegalDocumentActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DOC_TYPE = "doc_type"
        const val DOC_AGREEMENT = "agreement"
        const val DOC_PRIVACY = "privacy"

        fun agreementIntent(activity: Activity): Intent {
            return Intent(activity, LegalDocumentActivity::class.java)
                .putExtra(EXTRA_DOC_TYPE, DOC_AGREEMENT)
        }

        fun privacyIntent(activity: Activity): Intent {
            return Intent(activity, LegalDocumentActivity::class.java)
                .putExtra(EXTRA_DOC_TYPE, DOC_PRIVACY)
        }
    }

    private lateinit var binding: ActivityLegalDocumentBinding
    private var isAgreement = false
    private var hasReachedBottom = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLegalDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isAgreement = intent.getStringExtra(EXTRA_DOC_TYPE) == DOC_AGREEMENT
        setupUi()
    }

    private fun setupUi() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (isAgreement) {
            binding.toolbar.title = getString(R.string.user_agreement_title)
            binding.tvDocument.text = resources.openRawResource(R.raw.user_agreement).bufferedReader().use { it.readText() }
            binding.btnPrimary.text = getString(R.string.accept_agreement_after_read)
            binding.btnPrimary.isEnabled = false
            binding.tvScrollHint.visibility = View.VISIBLE
            binding.tvPrivacyLink.visibility = View.VISIBLE
            binding.tvPrivacyLink.movementMethod = LinkMovementMethod.getInstance()
            binding.tvPrivacyLink.setOnClickListener {
                startActivity(privacyIntent(this))
            }
            binding.btnPrimary.setOnClickListener {
                setResult(RESULT_OK)
                finish()
            }
            setupScrollValidation()
        } else {
            binding.toolbar.title = getString(R.string.privacy_policy_title)
            binding.tvDocument.text = resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }
            binding.btnPrimary.text = getString(R.string.close)
            binding.btnPrimary.isEnabled = true
            binding.tvScrollHint.visibility = View.GONE
            binding.tvPrivacyLink.visibility = View.GONE
            binding.btnPrimary.setOnClickListener { finish() }
        }
    }

    private fun setupScrollValidation() {
        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (hasReachedBottom) return@setOnScrollChangeListener
            val content = binding.tvDocument
            val reachedBottom = content.bottom <= scrollY + binding.scrollView.height
            if (reachedBottom) {
                hasReachedBottom = true
                binding.btnPrimary.isEnabled = true
                binding.tvScrollHint.text = getString(R.string.agreement_read_complete)
            }
        }
    }
}
