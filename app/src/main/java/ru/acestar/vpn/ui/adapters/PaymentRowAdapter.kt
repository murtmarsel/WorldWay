package ru.acestar.vpn.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.admin.UserPayment

/**
 * Компактный адаптер истории платежей в виде таблицы:
 * Дата | Тариф | Сумма | Статус
 */
class PaymentRowAdapter : ListAdapter<UserPayment, PaymentRowAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<UserPayment>() {
            override fun areItemsTheSame(a: UserPayment, b: UserPayment) = a.id == b.id
            override fun areContentsTheSame(a: UserPayment, b: UserPayment) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val date:   TextView = view.findViewById(R.id.tvRowDate)
        val tariff: TextView = view.findViewById(R.id.tvRowTariff)
        val amount: TextView = view.findViewById(R.id.tvRowAmount)
        val status: TextView = view.findViewById(R.id.tvRowStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_payment_row, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = getItem(position)
        val ctx = holder.itemView.context

        // Дата — только часть до пробела (без времени) для компактности
        holder.date.text = p.created_at.substringBefore(" ").ifBlank { p.created_at }

        // Тариф или описание
        holder.tariff.text = p.tariff?.takeIf { it.isNotBlank() }
            ?: p.description?.takeIf { it.isNotBlank() }
            ?: if (p.source == "ad") "Реклама" else "Подписка"

        // Сумма
        holder.amount.text = "${p.amount} ${p.currency}"

        val (statusText, statusColor) = when (p.status.lowercase()) {
            "succeeded", "success" -> "● оплачен" to R.color.connected
            "pending"              -> "● ожидание" to R.color.connecting
            "canceled", "failed"   -> "● отменён" to R.color.error
            else                   -> "● ${p.status}" to R.color.text_secondary
        }
        holder.status.text = statusText
        holder.status.setTextColor(ContextCompat.getColor(ctx, statusColor))
    }
}
