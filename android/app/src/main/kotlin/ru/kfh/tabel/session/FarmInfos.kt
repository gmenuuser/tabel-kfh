package ru.kfh.tabel.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import ru.kfh.core.model.Entities
import ru.kfh.core.model.FarmSettings
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.sync.Me
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.tabel.db.RecordDao
import ru.kfh.tabel.db.observe

/**
 * The header of the documents: the farm's name and the manager from `Me` (`/me` → `farm`), the responsible person,
 * address, phone and extra line from the synced settings row; the defaults where neither says.
 */
fun farmInfo(me: Me?, settings: FarmSettings?): FarmInfo {
    val base = settings?.let(FarmInfo::from) ?: FarmInfo.DEFAULT
    if (me == null) return base
    return base.copy(
        farmName = me.farmName.ifBlank { base.farmName },
        managerName = me.managerName.orEmpty().ifBlank { base.managerName },
    )
}

/** [farmInfo] over the session and the store, refreshed with every sync. */
fun farmInfoFlow(states: Flow<SessionState>, dao: RecordDao): Flow<FarmInfo> {
    val me = states.map { (it as? SessionState.SignedIn)?.me }
    val settings = dao.observe(Entities.farmSettings).map { rows ->
        rows.firstOrNull()?.let { RecordCodec.decode<FarmSettings>(Entities.farmSettings, it.id, it.json()) }
    }.flowOn(Dispatchers.Default)
    return combine(me, settings, ::farmInfo)
}
