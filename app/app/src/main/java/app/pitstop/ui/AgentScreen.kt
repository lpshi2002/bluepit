package app.pitstop.ui

import app.pitstop.AgentViewModel
import app.pitstop.AgentViewModel.UiState
import app.pitstop.agent.AgentAction
import app.pitstop.agent.AppliedInterval
import app.pitstop.agent.Planner
import app.pitstop.agent.Booking
import app.pitstop.agent.BookingOption
import app.pitstop.agent.DrivingAnalyzer
import app.pitstop.agent.ScheduleCatalog
import app.pitstop.agent.SevereCondition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 주행 기록으로 판단하는 조건. 나머지 가혹 조건은 사용자가 직접 고른다. */
private val AUTO_CONDITIONS = setOf(SevereCondition.SHORT_TRIPS, SevereCondition.CONGESTION, SevereCondition.OLD_VEHICLE)

/** 차 화면용 한 장짜리 UI. 왼쪽은 에이전트 제안과 주행 패턴, 오른쪽은 소모품 현황과 조작이다. */
@Composable
fun AgentScreen(vm: AgentViewModel) {
    val state = vm.state
    if (!vm.canInteract) {
        Surface(Modifier.fillMaxSize()) { Text("정차 후 정비 계획을 확인해 주세요.", Modifier.padding(32.dp), fontSize = 26.sp) }
        return
    }
    var cancel by remember { mutableStateOf<Booking?>(null) }
    var byShop by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(
                Modifier.weight(3f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state)
                if (!state.setupDone && state.spec != null) Setup(state, vm)
                else AgentCard(state, vm)
                if (vm.undoBooking != null) Secondary("10초 안에 되돌리기", vm::undo)
                state.items.mapNotNull { it.item.booking }.distinct().forEach { booking ->
                    val shop = app.pitstop.agent.DemoData.shops.find { it.id == booking.shopId }
                    Text("${shop?.name.orEmpty()} · ${booking.date.korean()} ${time(booking.hour, booking.minute)}")
                    Buttons {
                        Secondary("예약 계획 취소") { byShop = false; cancel = booking }
                        Secondary("정비소가 취소했어요") { byShop = true; cancel = booking }
                    }
                }
                state.notice?.let { Text(it, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary) }
                DrivingCard(state)
            }
            Column(
                Modifier.weight(2f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Secondary("차량·주행 환경 설정") { settingsOpen = true }
                Secondary(if (state.demo) "내 정비 기록으로 돌아가기" else "별도 시연 시작") { vm.switchDemo(!state.demo) }
                if (state.demo) DemoControls(vm)
                ItemList(state)
            }
        }
    }
    vm.pending?.let { option ->
        val action = state.action as? AgentAction.Propose
        val names = action?.let { (listOf(it.rule) + it.together).joinToString(", ") { r -> "${r.name} ${r.action.verb}" } }
        AlertDialog(onDismissRequest = vm::dismissSummary, title = { Text("이 계획을 확정할까요?") },
            text = { Text("${option.shop.name} · ${option.date.korean()} ${time(option.hour, option.minute)} · $names") },
            confirmButton = { Primary("확정", vm::confirm) },
            dismissButton = { Secondary("돌아가기", vm::dismissSummary) })
    }
    cancel?.let { booking ->
        val shop = app.pitstop.agent.DemoData.shops.find { it.id == booking.shopId }
        AlertDialog(onDismissRequest = { cancel = null }, title = { Text(if (byShop) "이 정비소를 제외하고 다시 찾을까요?" else "예약 계획을 취소할까요?") },
            text = { Text("${shop?.name} · ${booking.date.korean()} ${time(booking.hour, booking.minute)}") },
            confirmButton = { Primary("취소 확정") { vm.cancel(booking, byShop = byShop); cancel = null } },
            dismissButton = { Buttons {
                if (booking.hour == 18 && booking.minute == 10) Secondary("취소하고 경유지도 빼기") {
                    vm.cancel(booking, removeStop = true, byShop = byShop); cancel = null
                }
                Secondary("유지") { cancel = null }
            } })
    }
    if (settingsOpen) SettingsDialog(state, vm) { settingsOpen = false }
}

private fun time(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

@Composable
private fun Setup(state: UiState, vm: AgentViewModel) {
    val spec = state.spec ?: return
    var index by remember(spec.id) { mutableStateOf(-1) }
    var answers by remember(spec.id) { mutableStateOf(mapOf<String, Long?>()) }
    val rules = spec.rules.sortedBy { it.id !in Planner.frequentIds }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (index < 0) {
            Title("차종이 ${spec.label}인가요?")
            Body("다르면 차량·주행 환경 설정에서 골라 주세요.")
            Primary("이 차종으로 기록 입력") { index = 0 }
        } else {
            val rule = rules[index]
            Title("${rule.name} · 마지막 ${rule.action.verb}는 언제였나요?")
            Buttons {
                listOf("한 달 안" to 1L, "3개월쯤 전" to 3L, "6개월쯤 전" to 6L, "1년 넘음" to 13L,
                    "모르겠어요" to null).forEach { (label, months) ->
                    Secondary(label) {
                        answers = answers + (rule.id to months)
                        if (index == rules.lastIndex) vm.finishSetup(answers) else index++
                    }
                }
                if (rule.id !in Planner.frequentIds) Secondary("남은 항목은 교체한 적 없어요") { vm.finishSetup(answers) }
            }
        }
    }
}

@Composable
private fun Header(state: UiState) {
    Column {
        Text("피트스톱 · ${state.today.korean()}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        val km = state.currentKm?.let { "%,.0f km".format(it) } ?: "읽는 중"
        val kmSource = if (state.demo) "시연 기록" else if (state.kmFromVehicle) "차량 SDK" else "가상 값"
        val daily = "%.0f km".format(state.dailyKm) + if (state.usingDefaultDailyKm) " (기록 부족, 기본값)" else ""
        val places = if (state.placesFromNavi) "내비 즐겨찾기" else "샘플 장소"
        Text("주행거리 $km ($kmSource) · 하루 평균 $daily · 집·회사: $places", fontSize = 18.sp)
        val spec = state.spec?.let { "${it.label} (${it.manual} 취급설명서 기준)" } ?: "차종 확인 중"
        val detected = state.vehicle.energy?.label ?: "확인 중"
        val year = state.vehicle.modelYear?.let { " · 연식 ${it}년(차대번호)" }.orEmpty()
        Text("$spec · 차량 SDK 감지: $detected$year", fontSize = 18.sp)
    }
}

@Composable
private fun AgentCard(state: UiState, vm: AgentViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val action = state.action) {
                null -> Title("차량 정보를 읽고 있어요")
                AgentAction.AllGood -> {
                    Title("모든 소모품이 여유 있어요")
                    state.items.firstOrNull { it.dueDate != null }?.let {
                        Body("가장 먼저 오는 일: ${it.rule.name} ${it.rule.action.verb}, ${it.dueDate!!.korean()} 예상")
                    }
                }
                is AgentAction.Propose -> {
                    val option = action.options[state.optionIndex]
                    val row = state.items.firstOrNull { it.rule.id == action.rule.id }
                    val verb = action.rule.action.verb
                    if (action.applied.basis == AppliedInterval.Basis.WARNING) {
                        Title("${action.rule.name} 부족 · 바로 점검해 주세요")
                        Body(if (state.demo) "시연용 부족 신호입니다." else "차량에서 양 부족 신호를 받았어요.")
                    } else if (row?.item?.baselineCheck == true) {
                        Title("${action.rule.name} 기준 잡기 점검")
                        Body("기록 없음 · 추정")
                    } else if (action.reschedule) {
                        Title("예상보다 빨리 ${verb} 시점이 와요")
                        Body(
                            "${action.rule.name} ${verb} 예상일이 ${action.dueDate.korean()}로 당겨져 " +
                                "예약일 ${row?.item?.booking?.date?.korean()}보다 빨라요."
                        )
                    } else {
                        Title("${action.rule.name} ${verb}까지 ${daysUntil(state.today, action.dueDate)}일 남았어요")
                        val remaining = row?.remainingKm?.let { "남은 거리 약 %,.0f km · ".format(it) }.orEmpty()
                        Body("${remaining}예상일 ${action.dueDate.korean()}")
                    }
                    Reason(action.rule.normal.describe(), action.applied)
                    if (action.together.isNotEmpty()) {
                        Body("같은 날 함께: ${action.together.joinToString(", ") { "${it.name} ${it.action.verb}" }}")
                    }
                    Question(option, action.reschedule)
                    Buttons {
                        Primary(if (action.reschedule) "앞당기기" else "예약하기", vm::accept)
                        if (state.optionIndex + 1 < action.options.size) Secondary("다른 시간", vm::nextOption)
                        Secondary("나중에", vm::snooze)
                    }
                }
                is AgentAction.TooLate -> {
                    Title("${action.rule.name} ${action.rule.action.verb}가 급해요")
                    Body(
                        "예상일 ${action.dueDate.korean()}이 앱 예약 가능 기간(3일 뒤부터)보다 빨라요. " +
                            "블루핸즈 전화 예약(080-600-6000)으로 가장 빠른 날을 잡아 보세요."
                    )
                    Buttons {
                        Primary("${action.rule.action.verb} 완료") { vm.complete(listOf(action.rule)) }
                        Secondary("나중에", vm::snooze)
                    }
                }
                is AgentAction.VisitDay -> {
                    LaunchedEffect(action.booking) { vm.checkArrival(action) }
                    val delay = vm.delayed?.takeIf { it.first == action.booking }?.second
                    val overdue = action.booking.date.isBefore(state.today)
                    Title(if (overdue) "예약일이 지났어요. 정비를 마치셨나요?" else "오늘은 정비일이에요")
                    Body(action.rules.joinToString(", ") { "${it.name} ${it.action.verb}" })
                    Body(
                        "${action.booking.date.korean()} ${time(action.booking.hour, action.booking.minute)} · ${action.shop?.name ?: "정비소"} · " +
                            "확인번호 ${action.booking.confirmation}"
                    )
                    Buttons {
                        if (delay != null) Primary("다른 날로 옮기기") { vm.moveLater(action.booking) }
                        if (delay != Planner.Arrival.MOVE_ONLY) action.shop?.let { shop ->
                            Primary("바로 안내") { vm.routeTo(shop) }
                            Secondary("경유지로 추가") { vm.addWaypoint(shop) }
                        }
                        Secondary("정비 완료") { vm.complete(action.rules) }
                        if (overdue) Secondary("못 갔어요 · 다시 계획") { vm.cancel(action.booking) }
                    }
                }
            }
        }
    }
}

/** 지금 주기를 왜 쓰는지. 가혹 조건이면 어떤 조건 때문인지 보여 준다. */
@Composable
private fun Reason(normal: String, applied: AppliedInterval) {
    val text = when (applied.basis) {
        AppliedInterval.Basis.SEVERE ->
            "가혹 조건(${applied.because.joinToString(", ") { it.label }})이라 " +
                "적용 주기는 ${applied.interval.describe()}이에요. 통상 조건은 $normal"
        AppliedInterval.Basis.WARNING -> "정기 교체 예정일과 관계없이 양을 확인해 주세요."
        AppliedInterval.Basis.NONE -> "통상 조건에서 정해진 주기가 없어요."
        AppliedInterval.Basis.ADJUSTED -> "최근 주행의 가혹도에 따라 $normal 에서 ${applied.interval.describe()}로 앞당겼어요."
        AppliedInterval.Basis.FIRST -> "아직 교체 기록이 없어 최초 주기 ${applied.interval.describe()}를 써요."
        AppliedInterval.Basis.NORMAL -> "통상 조건 주기 ${applied.interval.describe()}"
    }
    val severe = applied.basis == AppliedInterval.Basis.SEVERE
    Text(text, fontSize = 20.sp, color = if (severe) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Question(option: BookingOption, reschedule: Boolean) {
    val verb = if (reschedule) "앞당길까요" else "예약할까요"
    Text(
        "${option.place.name} 근처 ${option.shop.name}(%.1f km), ".format(option.distanceKm) +
            "${option.date.korean()} ${time(option.hour, option.minute)} ${option.label}로 $verb? " +
            (option.detourMinutes?.let { "우회 약 %d분 · ".format(kotlin.math.round(it).toInt().coerceAtLeast(1)) }.orEmpty()) +
            (if (option.shop.partner) "제휴 가정 · 시연 빈 시간" else "일반 정비소 · 공식 채널에서 직접 접수"),
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 주행 기록에서 읽은 특징과 그로 판단한 가혹 조건. */
@Composable
private fun DrivingCard(state: UiState) {
    val profile = state.profile ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("최근 30일 주행 패턴", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val shortShare = profile.shortTripShare
            if (shortShare == null) {
                Text(
                    "주행 ${profile.tripCount}회 기록. ${DrivingAnalyzer.MIN_TRIPS}회 이상 모이면 짧은 주행·저속 주행 비율을 판단해요.",
                    fontSize = 18.sp,
                )
            } else {
                val slow = profile.slowTripShare?.let { " · 평균 %.0f km/h 미만 %.0f%%".format(DrivingAnalyzer.SLOW_TRIP_KMH, it * 100) }.orEmpty()
                Text(
                    "주행 ${profile.tripCount}회 · %.0f km 미만 %.0f%%".format(DrivingAnalyzer.SHORT_TRIP_KM, shortShare * 100) + slow,
                    fontSize = 18.sp,
                )
            }
            profile.scores.forEach { (condition, score) -> Text("${condition.label} 점수 %.2f".format(score)) }
            val conditions = state.conditions
            Text(
                if (conditions.isEmpty()) {
                    "확정된 가혹 조건 없음"
                } else {
                    "가혹 조건: " + conditions.joinToString(", ") { c ->
                        c.label + if (c in state.declared) " (직접 선택)" else " (주행 기록)"
                    }
                },
                fontSize = 18.sp,
                color = if (conditions.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun ItemList(state: UiState) {
    Text("소모품", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    state.items.forEach { row ->
        val status = row.item.booking?.let { "예약 ${it.date.korean()} ${time(it.hour, it.minute)}" }
            ?: row.item.snoozedUntil?.takeIf { it.isAfter(state.today) }?.let { "${it.korean()}까지 미룸" }
            ?: row.dueDate?.let { "예상 ${it.korean()}" }
            ?: "최근 주행이 없어 예측 불가"
        val remaining = row.remainingKm?.let { "남은 %,.0f km · ".format(it) }.orEmpty()
        val severe = row.applied.basis == AppliedInterval.Basis.SEVERE
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("${row.rule.name} ${row.rule.action.verb}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (row.item.estimated) Text(if (row.item.baselineCheck) "기록 없음 · 추정 · 기준 잡기 점검" else "기준점 추정")
                Text("$remaining$status", fontSize = 17.sp)
                Text(
                    "${row.applied.basis.label} ${row.applied.interval.describe()}",
                    fontSize = 15.sp,
                    color = if (severe) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsDialog(state: UiState, vm: AgentViewModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("닫기", fontSize = 20.sp) } },
        title = { Text("차량·주행 환경", fontSize = 26.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("차종 (현대차 취급설명서 정기 점검표 기준)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                ScheduleCatalog.specs.forEach { spec ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { vm.selectSpec(spec.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.spec?.id == spec.id, onClick = { vm.selectSpec(spec.id) })
                        val mismatch = if (vm.specMatchesVehicle(spec)) "" else " · 감지한 동력계와 다름"
                        Text("${spec.label} · ${spec.powertrain.label}$mismatch", fontSize = 19.sp)
                    }
                }
                Text("직접 알려 줄 주행 환경", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SevereCondition.entries.filter { it !in AUTO_CONDITIONS }.forEach { condition ->
                        FilterChip(
                            selected = condition in state.declared,
                            onClick = { vm.toggleDeclared(condition) },
                            label = { Text(condition.label, fontSize = 17.sp) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DemoControls(vm: AgentViewModel) {
    Text("시연 조작 · 가상 데이터", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Buttons {
        Secondary("시동 걸 때 제안", vm::demoIgnition)
        Secondary(if (vm.state.partnerDemo) "제휴 가정 끄기" else "제휴 가정 켜기", vm::togglePartner)
        Secondary(if (vm.state.low) "부족 신호 해제" else "오일 양 부족 신호", vm::demoWarning)
        Secondary("하루 지나기 +40km") { vm.demoDrive(1, 40.0, 2, 35.0) }
        Secondary("출퇴근 1주 +280km") { vm.demoDrive(7, 40.0, 2, 35.0) }
        Secondary("시내 단거리 1주") { vm.demoDrive(7, 20.0, 4, 15.0) }
        Secondary("장거리 +300km") { vm.demoDrive(1, 300.0, 1, 80.0) }
        val visit = vm.state.action as? AgentAction.VisitDay
        if (visit?.booking?.hour == 18 && visit.booking.minute == 10) {
            Secondary("20분 지각 시연") { vm.demoDelay(20) }
            Secondary("영업 종료 뒤 도착 시연") { vm.demoDelay(35) }
        }
        Secondary("처음부터", vm::demoReset)
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 32.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Body(text: String) = Text(text, fontSize = 22.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) =
    Button(onClick, Modifier.heightIn(min = 64.dp)) { Text(label, fontSize = 22.sp) }

@Composable
private fun Secondary(label: String, onClick: () -> Unit) =
    OutlinedButton(onClick, Modifier.heightIn(min = 64.dp)) { Text(label, fontSize = 20.sp) }

/** 올해가 아닌 날짜는 연도를 붙인다. 시연용 시계 기준이 아니라 기기 날짜 기준이다. */
private fun LocalDate.korean(): String {
    val prefix = if (year != LocalDate.now().year) "${year}년 " else ""
    return "$prefix${monthValue}월 ${dayOfMonth}일(${dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
}

private fun daysUntil(today: LocalDate, date: LocalDate) = ChronoUnit.DAYS.between(today, date)
