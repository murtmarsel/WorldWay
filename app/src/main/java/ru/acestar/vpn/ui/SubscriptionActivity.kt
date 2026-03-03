package ru.acestar.vpn.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.acestar.vpn.R
import ru.acestar.vpn.databinding.ActivitySubscriptionBinding

class SubscriptionActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySubscriptionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val stubClick = {
            Toast.makeText(this, R.string.subscription_stub, Toast.LENGTH_SHORT).show()
        }
        binding.btnExtend.setOnClickListener { stubClick() }
        binding.btnMonthly.setOnClickListener { stubClick() }
        binding.btnYearly.setOnClickListener { stubClick() }
    }
}
