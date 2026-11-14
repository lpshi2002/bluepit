package app.pitstop.voice

enum class VoiceCommand { ACCEPT, CONFIRM, NEXT, LATER, CANCEL, CONFIRM_CANCEL, ROUTE, WAYPOINT, DONE, SHOP_CANCEL, UNKNOWN }

fun voiceCommand(text: String): VoiceCommand = when (text.lowercase().replace(Regex("[\\s.,!?]+"), "")) {
    "응", "네", "예", "예약", "예약해줘", "응예약해줘", "예약하기", "앞당겨줘" -> VoiceCommand.ACCEPT
    "확정", "예약확정" -> VoiceCommand.CONFIRM
    "다른시간", "다른날", "다른날로옮겨줘" -> VoiceCommand.NEXT
    "나중에", "아니", "아니요", "다음에" -> VoiceCommand.LATER
    "취소", "예약취소", "예약취소해줘", "되돌려줘" -> VoiceCommand.CANCEL
    "취소확정" -> VoiceCommand.CONFIRM_CANCEL
    "예약취소됐어", "정비소가취소했어" -> VoiceCommand.SHOP_CANCEL
    "바로안내", "길안내", "안내해줘" -> VoiceCommand.ROUTE
    "경유지추가", "경유지로추가해줘" -> VoiceCommand.WAYPOINT
    "정비완료", "정비했어" -> VoiceCommand.DONE
    else -> VoiceCommand.UNKNOWN
}
