package ru.acestar.vpn.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.admin.UserPayment

class PaymentsAdapter : ListAdapter<UserPayment, PaymentsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<UserPayment>() {
            override fun areItemsTheSame(a: UserPayment, b: UserPayment) = a.id == b.id
            override fun areContentsTheSame(a: UserPayment, b: UserPayment) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivPaymentIcon)
        val description: TextView = view.findViewById(R.id.tvPaymentDescription)
        val date: TextView = view.findViewById(R.id.tvPaymentDate)
        val tariff: TextView = view.findViewById(R.id.tvPaymentTariff)
        val amount: TextView = view.findViewById(R.id.tvPaymentAmount)
        val status: TextView = view.findViewById(R.id.tvPaymentStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_payment, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = getItem(position)
        val ctx = holder.itemView.context

        // Описание
        holder.description.text = p.description?.takeIf { it.isNotBlank() }
            ?: when (p.source) {
                "ad" -> "Реклама (Hour)"
                else -> "Подписка"
            }

        // Дата
        holder.date.text = p.created_at

        // Тариф + период
        val tariffText = buildString {
            p.tariff?.let { append(it) }
            p.period?.let { append(" · $it") }
        }
        if (tariffText.isNotBlank()) {
            holder.tariff.text = tariffText
            holder.tariff.visibility = View.VISIBLE
        } else {
            holder.tariff.visibility = View.GONE
        }

        // Сумма
        holder.amount.text = "${p.amount} ${p.currency}"

        // Статус
        val (statusText, statusColor) = when (p.status.lowercase()) {
            "succeeded", "success" -> "Оплачен" to R.color.connected
            "pending"              -> "Ожидание" to R.color.connecting
            "canceled", "failed"   -> "Отменён" to R.color.error
            else                   -> p.status to R.color.text_secondary
        }
        holder.status.text = statusText
        holder.status.setTextColor(ContextCompat.getColor(ctx, statusColor))

        // Иконка
        val iconTint = if (p.source == "ad") R.color.accent_yellow else R.color.primary
        holder.icon.setColorFilter(ContextCompat.getColor(ctx, iconTint))
    }
}
