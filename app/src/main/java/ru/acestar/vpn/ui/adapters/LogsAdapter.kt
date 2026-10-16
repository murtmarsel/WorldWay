package ru.acestar.vpn.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.admin.UserLog

class LogsAdapter : ListAdapter<UserLog, LogsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<UserLog>() {
            override fun areItemsTheSame(a: UserLog, b: UserLog) = a.id == b.id
            override fun areContentsTheSame(a: UserLog, b: UserLog) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val action: TextView = view.findViewById(R.id.tvLogAction)
        val date: TextView = view.findViewById(R.id.tvLogDate)
        val details: TextView = view.findViewById(R.id.tvLogDetails)
        val ip: TextView = view.findViewById(R.id.tvLogIp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val log = getItem(position)
        holder.action.text = log.action
        holder.date.text = log.created_at
        holder.details.text = log.details.takeIf { it.isNotBlank() } ?: "—"
        if (!log.ip_address.isNullOrBlank()) {
            holder.ip.text = "IP: ${log.ip_address}"
            holder.ip.visibility = View.VISIBLE
        } else {
            holder.ip.visibility = View.GONE
        }
    }
}
