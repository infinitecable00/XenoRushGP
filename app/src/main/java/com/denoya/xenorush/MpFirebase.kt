package com.denoya.xenorush

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Query
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

/**
 * Firebase Realtime Database transport for multiplayer.
 * Layout:  rooms/{id}       status, trackId, startAt, secondJoinAt, createdAt, players/{uid}
 *          roomState/{id}/{uid}   latest NetState (removed on disconnect)
 *          roomEvents/{id}/{push} NetEvent stream
 */
class MpFirebase(app: FirebaseApp) : MpBackend {
    private val auth = FirebaseAuth.getInstance(app)
    private val db = FirebaseDatabase.getInstance(app)
    @Volatile private var offset = 0L
    private val listeners = ArrayList<Pair<Query, Any>>()

    init {
        db.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { offset = (s.getValue(Double::class.java) ?: 0.0).toLong() }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    override fun uid(): String = auth.currentUser?.uid ?: ""
    override fun serverNow(): Long = System.currentTimeMillis() + offset

    private fun rooms() = db.getReference("rooms")

    private fun playerMap(p: MpPlayer): Map<String, Any> = mapOf(
        "name" to p.name.take(16), "vehicle" to p.vehicleId, "e" to p.e, "a" to p.a, "g" to p.g, "w" to p.w,
        "tracks" to p.ownedTracks.sorted().joinToString(","), "pref" to p.preferredTrack, "joinedAt" to p.joinedAt
    )

    override fun joinOrCreate(me: MpPlayer, onJoined: (String) -> Unit, onError: (String) -> Unit) {
        if (uid().isEmpty()) { onError("Sign in with Google first"); return }
        rooms().orderByChild("status").equalTo("waiting").limitToFirst(10).get()
            .addOnSuccessListener { snap ->
                val ids = snap.children.mapNotNull { it.key }
                tryJoin(ids, 0, me, onJoined, onError)
            }
            .addOnFailureListener { onError("Matchmaking failed: ${it.message ?: "network error"}") }
    }

    private fun tryJoin(ids: List<String>, i: Int, me: MpPlayer, onJoined: (String) -> Unit, onError: (String) -> Unit) {
        if (i >= ids.size) { create(me, onJoined, onError); return }
        val id = ids[i]
        val now = serverNow()
        rooms().child(id).runTransaction(object : Transaction.Handler {
            override fun doTransaction(cur: MutableData): Transaction.Result {
                if (cur.value == null) return Transaction.success(cur) // not cached yet; server retries with real data
                if (cur.child("status").getValue(String::class.java) != "waiting") return Transaction.abort()
                val players = cur.child("players")
                if (players.childrenCount >= MpRules.MAX_PLAYERS && !players.hasChild(me.uid)) return Transaction.abort()
                players.child(me.uid).value = playerMap(me.copy(joinedAt = now))
                if (players.childrenCount == 2L && (cur.child("secondJoinAt").getValue(Long::class.java) ?: 0L) == 0L) {
                    cur.child("secondJoinAt").value = now
                }
                return Transaction.success(cur)
            }
            override fun onComplete(e: DatabaseError?, committed: Boolean, s: DataSnapshot?) {
                if (e == null && committed && s != null && s.child("players").hasChild(me.uid)) {
                    armDisconnect(id, me.uid); onJoined(id)
                } else tryJoin(ids, i + 1, me, onJoined, onError)
            }
        })
    }

    private fun create(me: MpPlayer, onJoined: (String) -> Unit, onError: (String) -> Unit) {
        val ref = rooms().push()
        val id = ref.key ?: run { onError("Could not create a room"); return }
        val now = serverNow()
        val data = mapOf("status" to "waiting", "createdAt" to now, "trackId" to 0, "startAt" to 0L,
            "secondJoinAt" to 0L, "players" to mapOf(me.uid to playerMap(me.copy(joinedAt = now))))
        ref.setValue(data).addOnSuccessListener { armDisconnect(id, me.uid); onJoined(id) }
            .addOnFailureListener { onError("Could not create a room: ${it.message ?: "network error"}") }
    }

    private fun armDisconnect(id: String, uid: String) {
        rooms().child(id).child("players").child(uid).onDisconnect().removeValue()
        db.getReference("roomState").child(id).child(uid).onDisconnect().removeValue()
    }

    private fun parsePlayer(uid: String, s: DataSnapshot) = MpPlayer(
        uid, s.child("name").getValue(String::class.java) ?: "Racer",
        (s.child("vehicle").getValue(Long::class.java) ?: 0L).toInt(),
        (s.child("e").getValue(Long::class.java) ?: 1L).toInt(), (s.child("a").getValue(Long::class.java) ?: 1L).toInt(),
        (s.child("g").getValue(Long::class.java) ?: 1L).toInt(), (s.child("w").getValue(Long::class.java) ?: 1L).toInt(),
        (s.child("tracks").getValue(String::class.java) ?: "").split(",").mapNotNull { it.toIntOrNull() }.toSet(),
        (s.child("pref").getValue(Long::class.java) ?: 0L).toInt(),
        s.child("joinedAt").getValue(Long::class.java) ?: 0L
    )

    override fun watchRoom(roomId: String, onChange: (MpRoom?) -> Unit) {
        val q = rooms().child(roomId)
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) { onChange(null); return }
                val players = s.child("players").children.associate { (it.key ?: "") to parsePlayer(it.key ?: "", it) }
                onChange(MpRoom(roomId, s.child("status").getValue(String::class.java) ?: "waiting", players,
                    (s.child("trackId").getValue(Long::class.java) ?: 0L).toInt(),
                    s.child("startAt").getValue(Long::class.java) ?: 0L,
                    s.child("secondJoinAt").getValue(Long::class.java) ?: 0L))
            }
            override fun onCancelled(e: DatabaseError) { onChange(null) }
        }
        q.addValueEventListener(l); synchronized(listeners) { listeners.add(q to l) }
    }

    override fun startRoom(roomId: String, trackId: Int, startAt: Long) {
        rooms().child(roomId).runTransaction(object : Transaction.Handler {
            override fun doTransaction(cur: MutableData): Transaction.Result {
                if (cur.value == null) return Transaction.success(cur)
                if (cur.child("status").getValue(String::class.java) != "waiting") return Transaction.abort()
                cur.child("status").value = "racing"
                cur.child("trackId").value = trackId
                cur.child("startAt").value = startAt
                return Transaction.success(cur)
            }
            override fun onComplete(e: DatabaseError?, committed: Boolean, s: DataSnapshot?) {}
        })
    }

    override fun publishState(roomId: String, s: NetState) {
        val u = uid(); if (u.isEmpty()) return
        db.getReference("roomState").child(roomId).child(u).setValue(mapOf(
            "z" to s.z.toDouble(), "lap" to s.lap, "x" to s.x.toDouble(), "sp" to s.speed.toDouble(), "en" to s.energy,
            "al" to s.alive, "fin" to s.finished, "fm" to s.finishMs, "sh" to s.shield, "rf" to s.reflect, "bo" to s.boost, "t" to s.t))
    }

    private fun parseState(s: DataSnapshot): NetState? = try {
        fun d(k: String) = (s.child(k).value as? Number)?.toDouble() ?: 0.0
        fun b(k: String) = s.child(k).getValue(Boolean::class.java) ?: false
        NetState(d("z").toFloat(), d("lap").toInt(), d("x").toFloat(), d("sp").toFloat(), d("en").toInt(),
            b("al"), b("fin"), d("fm").toLong(), b("sh"), b("rf"), b("bo"), d("t").toLong())
    } catch (_: Exception) { null }

    override fun watchStates(roomId: String, onState: (String, NetState?) -> Unit) {
        val q = db.getReference("roomState").child(roomId)
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) { s.key?.let { onState(it, parseState(s)) } }
            override fun onChildChanged(s: DataSnapshot, p: String?) { s.key?.let { onState(it, parseState(s)) } }
            override fun onChildRemoved(s: DataSnapshot) { s.key?.let { onState(it, null) } }
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        q.addChildEventListener(l); synchronized(listeners) { listeners.add(q to l) }
    }

    override fun sendEvent(roomId: String, e: NetEvent) {
        db.getReference("roomEvents").child(roomId).push().setValue(mapOf(
            "ty" to e.type, "fr" to e.from, "wp" to e.weapon, "z" to e.z.toDouble(), "x" to e.x.toDouble(),
            "sp" to e.speed.toDouble(), "dm" to e.damage.toDouble(), "tg" to e.target, "rf" to e.reflected))
    }

    override fun watchEvents(roomId: String, onEvent: (NetEvent) -> Unit) {
        val q = db.getReference("roomEvents").child(roomId)
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                try {
                    fun d(k: String) = (s.child(k).value as? Number)?.toDouble() ?: 0.0
                    onEvent(NetEvent(s.child("ty").getValue(String::class.java) ?: return,
                        s.child("fr").getValue(String::class.java) ?: return, d("wp").toInt(),
                        d("z").toFloat(), d("x").toFloat(), d("sp").toFloat(), d("dm").toFloat(),
                        s.child("tg").getValue(String::class.java) ?: "", s.child("rf").getValue(Boolean::class.java) ?: false))
                } catch (_: Exception) {}
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        q.addChildEventListener(l); synchronized(listeners) { listeners.add(q to l) }
    }

    override fun leave(roomId: String) {
        synchronized(listeners) {
            for ((q, l) in listeners) {
                when (l) {
                    is ValueEventListener -> q.removeEventListener(l)
                    is ChildEventListener -> q.removeEventListener(l)
                }
            }
            listeners.clear()
        }
        val u = uid(); if (u.isEmpty()) return
        val p: DatabaseReference = rooms().child(roomId).child("players").child(u)
        p.onDisconnect().cancel(); p.removeValue()
        val st = db.getReference("roomState").child(roomId).child(u)
        st.onDisconnect().cancel(); st.removeValue()
    }
}
