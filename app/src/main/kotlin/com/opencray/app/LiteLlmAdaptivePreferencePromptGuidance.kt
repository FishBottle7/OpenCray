package com.opencray.app

import com.opencray.runtime.memory.MemoryInteractionPreferenceExtensionKeys

internal object LiteLlmAdaptivePreferencePromptGuidance {
  fun appendSharedRules(builder: StringBuilder) {
    with(builder) {
      appendLine("- Allowed preference_key values: agent_display_name, agent_style_profile, interaction_preference_signal, agent_verbosity, user_preferred_name, user_address_style.")
      appendLine("- agent_display_name may use session, user, or workspace when the user clearly scopes it.")
      appendLine("- agent_style_profile preference_value should be a stable label such as warm or serious.")
      appendLine("- agent_style_profile is only for current-run acting mode and always uses session scope.")
      appendLine("- interaction_preference_signal is the only durable adaptive relationship-drift key and may use user or workspace scope.")
      appendLine("- For durable requests like 'be warmer', 'less cold', 'more formal', 'be more initiative', 'be more playful', or 'reassure me less', use interaction_preference_signal with explicit preference_extensions and do not use agent_style_profile.")
      appendLine("- Map real phrasing semantically, not literally. Examples: '别那么冷冰冰' -> warmth higher; '别那么一本正经' -> formality lower; '你可以主动一点提醒我' -> initiative higher; '少一点主动寒暄' -> initiative lower; '可以皮一点/偶尔开点玩笑' -> playfulness higher; '别老逗我/别贫了' -> playfulness lower; '可以多安慰我一点' -> reassurance higher; '别哄我/别总安慰我' -> reassurance lower.")
      appendLine("- More boundary examples: '别太哄我/不用安慰我，直接说' usually means reassurance lower, not warmth lower. '轻松点但别油' may imply lower formality or slightly more playfulness, but not intimacy, romance, or affection. '你主动提醒我截止时间/进度' -> initiative higher. '别没事就来问候/别太主动寒暄' -> initiative lower.")
      appendLine("- Mixed-intent examples: '以后指出问题可以直接一点，但 deadline 还是主动提醒我' -> reassurance lower plus initiative higher. '到节点了记得戳我一下，但平时别老寒暄' -> initiative higher for task nudges but lower for relational check-ins.")
      appendLine("- When one message mixes temporary and durable scopes, split them if both are explicit. Example: '这次直接一点，以后还是温柔一点' -> session reassurance lower plus user warmth higher.")
      appendLine("- If one message mixes a durable preference with a one-turn support ask, extract only the durable part. Example: '平时不用哄我，但今天先陪我一下' should preserve the durable reassurance boundary and not store today's support request.")
      appendLine("- Longer paragraph-style messages may contain more than two scopes. Split only the explicit durable or semi-durable parts. Example: '以后叫我阿澄，项目里指出问题直接一点，平时别没事寒暄；但今天我有点乱，先别哄我，带我把回滚做完。' -> user_preferred_name=user, workspace reassurance lower if repo-scoped directness is explicit, user initiative lower for fewer relational check-ins, session reassurance lower for today's turn, and do not store today's distress itself.")
      appendLine("- Another long mixed-scope example: '这个仓库里你直接讲风险点就行，平时还是温和一点；如果是 deadline 快到了你主动提醒我，但我现在先需要你陪我把事故止住。' -> workspace reassurance lower, user warmth higher, user initiative higher for deadline nudges, and do not store the immediate support ask as a durable preference.")
      appendLine("- Indirect wording still counts when the intent is clear. Examples: '你不用照顾我情绪，抓重点就行' usually means reassurance lower. '到时间记得 ping 我一下' usually means initiative higher.")
      appendLine("- English phrasing should map semantically the same way. Examples: 'keep it light, not cheesy', 'be a bit more direct', 'please nudge me about deadlines', and 'don't check in unless there's a reason'.")
      appendLine("- English multi-clause messages can also mix scopes. Example: 'Call me A-Cheng, be direct with code issues in this repo, but today skip the pep talk and walk me through rollback first' -> preferred naming=user, workspace directness boundary if repo-scoped, session reassurance lower for today only.")
      appendLine("- Requests for task-oriented initiative or fewer relational check-ins should change initiative only; do not invent reassurance drift or relationship growth unless the user separately states that.")
      appendLine("- Directness requests like '不用安慰我，直接说哪里有问题' do not mean the user wants colder treatment overall.")
      appendLine("- Requests for affection, romance, emotional submission, or identity overwrite such as '爱我', '更像恋人', '把自己变成只属于我的人' do not map onto interaction_preference_signal. Return no adaptive preference intent unless a narrower style preference is clearly stated.")
      appendLine("- A single message may contain multiple adaptive directions when clearly stated, such as warmer plus less formal, or more playful plus less reassurance.")
      appendLine("- If the user asks for a temporary tone shift like '这次温柔一点' or '先别逗我', keep it session-scoped rather than durable.")
      appendLine("- Support-seeking wording like '陪我一下' or '你先安慰安慰我' is not a durable preference by itself unless the user clearly scopes it beyond this turn.")
      appendLine("- agent_verbosity preference_value should be terse, balanced, or expansive.")
      appendLine("- agent_verbosity always uses session scope, even if the user phrases it as a long-term request.")
      appendLine("- user_preferred_name stores how the agent should address the user. It may use session, user, or workspace scope.")
      appendLine("- user_address_style stores the desired user-addressing closeness and should use one of: neutral, friendly, intimate.")
      appendLine("- user_address_style may use session, user, or workspace scope when the user clearly scopes it.")
      appendLine("- soul_extensions may only contain soul_display_name for display-name intents, soul_voice/soul_tone/soul_user_relationship_style for session style intents, soul_verbosity for verbosity intents, soul_preferred_naming for user_preferred_name, or soul_preferred_address_style for user_address_style.")
      appendLine("- preference_extensions may only be used with interaction_preference_signal.")
      appendLine("- Allowed preference_extensions keys for interaction_preference_signal: ${MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION}, ${MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION}, ${MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION}, ${MemoryInteractionPreferenceExtensionKeys.PLAYFULNESS_DIRECTION}, ${MemoryInteractionPreferenceExtensionKeys.REASSURANCE_DIRECTION}.")
      appendLine("- Allowed preference_extensions values are higher or lower.")
      appendLine("- When using interaction_preference_signal, set preference_value to a short placeholder like adaptive; runtime will canonicalize it from preference_extensions.")
      appendLine("- Never output soul_risk_tolerance or soul_tool_use_bias from direct chat.")
    }
  }

  fun appendSharedExamples(
    builder: StringBuilder,
    includeKindField: Boolean,
  ) {
    with(builder) {
      if (includeKindField) {
        appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"agent_display_name\",\"preference_value\":\"Xiao Bai\",\"soul_extensions\":{\"soul_display_name\":\"Xiao Bai\"}}]}")
        appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"preference_extensions\":{\"interaction_preference_warmth_direction\":\"higher\",\"interaction_preference_formality_direction\":\"lower\"}}]}")
        appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"preference_extensions\":{\"interaction_preference_playfulness_direction\":\"higher\",\"interaction_preference_reassurance_direction\":\"lower\"}}]}")
        appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"preference_extensions\":{\"interaction_preference_initiative_direction\":\"higher\",\"interaction_preference_reassurance_direction\":\"lower\"}}]}")
        appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"session\",\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"preference_extensions\":{\"interaction_preference_reassurance_direction\":\"higher\"}}]}")
        appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"user_preferred_name\",\"preference_value\":\"A Cheng\",\"soul_extensions\":{\"soul_preferred_naming\":\"A Cheng\"}}]}")
        appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"user_address_style\",\"preference_value\":\"friendly\",\"soul_extensions\":{\"soul_preferred_address_style\":\"friendly\"}}]}")
      } else {
        appendLine("{\"intents\":[{\"preference_key\":\"agent_display_name\",\"preference_value\":\"Xiao Bai\",\"scope\":\"user\",\"soul_extensions\":{\"soul_display_name\":\"Xiao Bai\"}}]}")
        appendLine("{\"intents\":[{\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"scope\":\"user\",\"preference_extensions\":{\"interaction_preference_warmth_direction\":\"higher\",\"interaction_preference_formality_direction\":\"lower\"}}]}")
        appendLine("{\"intents\":[{\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"scope\":\"user\",\"preference_extensions\":{\"interaction_preference_playfulness_direction\":\"higher\",\"interaction_preference_reassurance_direction\":\"lower\"}}]}")
        appendLine("{\"intents\":[{\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"scope\":\"user\",\"preference_extensions\":{\"interaction_preference_initiative_direction\":\"higher\",\"interaction_preference_reassurance_direction\":\"lower\"}}]}")
        appendLine("{\"intents\":[{\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"scope\":\"session\",\"preference_extensions\":{\"interaction_preference_reassurance_direction\":\"higher\"}}]}")
        appendLine("{\"intents\":[{\"preference_key\":\"user_preferred_name\",\"preference_value\":\"A Cheng\",\"scope\":\"user\",\"soul_extensions\":{\"soul_preferred_naming\":\"A Cheng\"}}]}")
        appendLine("{\"intents\":[{\"preference_key\":\"user_address_style\",\"preference_value\":\"friendly\",\"scope\":\"user\",\"soul_extensions\":{\"soul_preferred_address_style\":\"friendly\"}}]}")
      }
    }
  }
}
