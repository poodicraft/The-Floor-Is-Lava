package com.lava.floorislava

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lava.floorislava.databinding.ActivityLeaderboardBinding
import com.lava.floorislava.databinding.ItemLeaderboardBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Global, friends and area leaderboards, ranked by points. */
class LeaderboardActivity : AppCompatActivity() {

    private enum class Board { GLOBAL, FRIENDS, AREA }

    private lateinit var binding: ActivityLeaderboardBinding
    private val adapter = LeaderboardAdapter()
    private var board = Board.GLOBAL
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Cloud.isSignedIn(this)) {
            Toast.makeText(this, "Sign in to see the leaderboards", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding = ActivityLeaderboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.leaderboardList.layoutManager = LinearLayoutManager(this)
        binding.leaderboardList.adapter = adapter

        binding.leaderboardClose.setOnClickListener { finish() }
        binding.tabGlobal.setOnClickListener { show(Board.GLOBAL) }
        binding.tabFriends.setOnClickListener { show(Board.FRIENDS) }
        binding.tabArea.setOnClickListener { show(Board.AREA) }
        binding.addFriendButton.setOnClickListener { promptAddFriend() }

        show(Board.GLOBAL)
    }

    private fun show(target: Board) {
        board = target
        val tabs = mapOf(Board.GLOBAL to binding.tabGlobal, Board.FRIENDS to binding.tabFriends, Board.AREA to binding.tabArea)
        for ((b, tab) in tabs) {
            val selected = b == target
            tab.setBackgroundResource(if (selected) R.drawable.bg_mode_pill_selected else 0)
            tab.setTextColor(ContextCompat.getColor(this, if (selected) R.color.lava_black else R.color.text_secondary))
        }
        binding.addFriendButton.visibility = if (target == Board.FRIENDS) View.VISIBLE else View.GONE
        load()
    }

    private fun load() {
        loadJob?.cancel()
        adapter.submit(emptyList(), null)
        binding.leaderboardProgress.visibility = View.VISIBLE
        binding.leaderboardEmpty.visibility = View.GONE
        binding.myRankText.visibility = View.GONE

        val target = board
        loadJob = lifecycleScope.launch {
            try {
                val me = Cloud.myProfile()
                val players: List<PlayerProfile>
                val subtitle: String
                var emptyMessage = "No players yet — be the first!"
                when (target) {
                    Board.GLOBAL -> {
                        players = Cloud.globalLeaderboard()
                        subtitle = "Top 50 players worldwide"
                    }
                    Board.FRIENDS -> {
                        players = Cloud.friendsLeaderboard()
                        subtitle = "You and your friends"
                        emptyMessage = "Add friends by username, or race someone in multiplayer — they're added automatically."
                    }
                    Board.AREA -> {
                        val areaKey = me?.areaKey.orEmpty()
                        players = Cloud.areaLeaderboard(areaKey)
                        subtitle = if (me?.areaName.isNullOrBlank()) "Players near you" else "Players in ${me?.areaName}"
                        if (areaKey.isBlank()) {
                            emptyMessage = "Play a round first so the game can work out which area you're in."
                        }
                    }
                }
                binding.leaderboardProgress.visibility = View.GONE
                binding.leaderboardSubtitle.text = subtitle
                adapter.submit(players, Cloud.uid)
                if (players.isEmpty()) {
                    binding.leaderboardEmpty.text = emptyMessage
                    binding.leaderboardEmpty.visibility = View.VISIBLE
                }
                showMyRank(me, players)
            } catch (e: Exception) {
                binding.leaderboardProgress.visibility = View.GONE
                binding.leaderboardEmpty.text = "Couldn't load the leaderboard.\nCheck your internet connection.\n\n(${e.message})"
                binding.leaderboardEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun showMyRank(me: PlayerProfile?, players: List<PlayerProfile>) {
        if (me == null) return
        val index = players.indexOfFirst { it.uid == me.uid }
        binding.myRankText.text = if (index >= 0) {
            "You're #${index + 1}  ·  ${me.points} pts  ·  ${me.wins} wins"
        } else {
            "You: ${me.points} pts  ·  ${me.wins} wins — not in this top list yet"
        }
        binding.myRankText.visibility = View.VISIBLE
    }

    private fun promptAddFriend() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "Friend's username"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add a friend")
            .setView(container)
            .setPositiveButton("Add") { _, _ -> addFriend(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addFriend(username: String) {
        if (username.isBlank()) return
        lifecycleScope.launch {
            try {
                val friend = Cloud.addFriendByUsername(username)
                Toast.makeText(this@LeaderboardActivity, "${friend.username} is now your friend!", Toast.LENGTH_SHORT).show()
                if (board == Board.FRIENDS) load()
            } catch (e: PlayerNotFoundException) {
                Toast.makeText(this@LeaderboardActivity, "No player called \"$username\"", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@LeaderboardActivity, e.message ?: "Couldn't add friend", Toast.LENGTH_LONG).show()
            }
        }
    }

    private class LeaderboardAdapter : RecyclerView.Adapter<LeaderboardAdapter.Row>() {

        private var players: List<PlayerProfile> = emptyList()
        private var myUid: String? = null

        class Row(val binding: ItemLeaderboardBinding) : RecyclerView.ViewHolder(binding.root)

        fun submit(list: List<PlayerProfile>, me: String?) {
            players = list
            myUid = me
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
            Row(ItemLeaderboardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = players.size

        override fun onBindViewHolder(holder: Row, position: Int) {
            val player = players[position]
            val context = holder.itemView.context
            val isMe = player.uid == myUid
            with(holder.binding) {
                rowRank.text = when (position) {
                    0 -> "🥇"
                    1 -> "🥈"
                    2 -> "🥉"
                    else -> "#${position + 1}"
                }
                rowName.text = if (isMe) "${player.username} (you)" else player.username
                val details = mutableListOf("${player.wins} wins")
                if (player.multiplayerWins > 0) details.add("${player.multiplayerWins} race wins")
                if (player.areaName.isNotBlank()) details.add(player.areaName)
                rowDetail.text = details.joinToString("  ·  ")
                rowPoints.text = player.points.toString()
                rowRoot.setBackgroundResource(if (isMe) R.drawable.bg_card_me else R.drawable.bg_card)
                rowName.setTextColor(ContextCompat.getColor(context, if (isMe) R.color.lava_gold else R.color.text_primary))
            }
        }
    }
}
