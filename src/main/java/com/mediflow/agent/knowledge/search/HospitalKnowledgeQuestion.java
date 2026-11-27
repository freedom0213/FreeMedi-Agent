package com.mediflow.agent.knowledge.search;

import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import java.util.List;
import java.util.regex.Pattern;

/** Coverage of the patient-facing materials verified for this release. */
public final class HospitalKnowledgeQuestion {
    private static final Pattern MATERIAL_REQUEST = Pattern.compile(
            "预约规则|挂号规则|预约.{0,6}(规则|规定|流程|确认|成功)|放号|重复.{0,5}(预约|挂号|提交)|"
            + "爽约|取消|改约|退号|就诊.{0,5}(准备|须知|注意|证明|证件|凭证)|"
            + "到院|到医院|就诊.*要|身份证|预约编号|预约号(?!源)|取号|候诊|叫号|迟到|提前.{0,6}到|"
            + "(要|需要|该|得)带|带什么|带哪些|检查前|空腹|禁食|停药|医保|报销|停车|宠物");
    private static final Pattern UNSUPPORTED_DETAIL = Pattern.compile(
            "医保|报销|停车|宠物|费用|收费|发票|支付|陪同|儿童|小孩|未成年|护照|港澳|代办|代挂|"
            + "绿通|病假|爽约|急诊|饮食|空腹|禁食|停药|用药|肠镜|胃镜|抽血|核磁|麻醉|镇静|检查结果|"
            + "开诊前|提前.{0,5}(分钟|小时|天)|几点.{0,5}(放号|到)|放号.{0,5}几点");
    private static final Pattern CHANGE_REQUEST = Pattern.compile(
            "^(请|能不能|可以|帮我|麻烦|我想|我需要|我要|那就|先|给我|可以帮我)*"
            + "(把.{0,12})?(改成|改到|换成|换到|调整到|换医生)");
    private static final Pattern DIRECT_COMMAND = Pattern.compile(
            "^(请|帮我|麻烦|我想|我要|那就|给我)*确认预约[。！!]*$");
    private static final Pattern DOCTOR_PROFILE_REQUEST = Pattern.compile(
            "专长|擅长|(医生|医师|主任).{0,8}(专业方向|接诊范围|简介|介绍|资料|主要看|看什么)");
    private static final Pattern DEPARTMENT_PROFILE_REQUEST = Pattern.compile(
            "科室.{0,6}(介绍|资料|看什么|服务范围|接诊范围)|科.{0,8}(门诊介绍|看什么|看哪些|服务范围|接诊范围)");

    record Coverage(KnowledgeDocType type, String document, List<String> headings) {
        boolean includes(KnowledgeRetriever.RetrievedChunk chunk) {
            return type == chunk.docType() && document.equals(chunk.docNo()) && headings.contains(chunk.heading());
        }
    }

    private HospitalKnowledgeQuestion() { }

    public static boolean isConsultation(String question) {
        String text = question.strip();
        return !CHANGE_REQUEST.matcher(text).find() && !DIRECT_COMMAND.matcher(text).matches()
                && (MATERIAL_REQUEST.matcher(text).find() || isProfileQuestion(text));
    }

    public static boolean isProfileQuestion(String question) {
        return profileType(question) != null;
    }

    static KnowledgeDocType profileType(String question) {
        if (question == null || CHANGE_REQUEST.matcher(question.strip()).find()) return null;
        if (DOCTOR_PROFILE_REQUEST.matcher(question).find()
                && !question.matches("(?s).*(我想挂|我想预约|帮我挂|帮我预约|帮我找).{0,8}(擅长|专长).{0,30}的(医生|医师).*")) {
            return KnowledgeDocType.DOCTOR_PROFILE;
        }
        return DEPARTMENT_PROFILE_REQUEST.matcher(question).find() ? KnowledgeDocType.DEPARTMENT_INTRO : null;
    }

    static Coverage coverage(String question) {
        String text = question.strip();
        if (UNSUPPORTED_DETAIL.matcher(text).find()) return null;
        if (text.contains("取消") || text.contains("改约") || text.contains("退号")) return rule("取消与改约入口");
        if (text.contains("重复") || text.contains("同一个号") || text.contains("重试")) return rule("重复提交");
        if (text.contains("放号") || text.contains("可约日期")) return rule("可约时间与号源");
        if (text.contains("预约规则") || text.contains("挂号规则")
                || text.matches(".*预约.{0,6}(规则|规定|流程|确认|成功).*")) {
            return rule("预约与确认", "可约时间与号源", "重复提交");
        }
        if (text.contains("检查前")) {
            return new Coverage(KnowledgeDocType.PREPARATION, "PREP-001", List.of("通用原则"));
        }
        if (text.matches(".*(证件|凭证|身份证|预约编号|预约号|需要带|要带|带什么|带哪些|携带).*")) {
            return visit("证件与凭证");
        }
        if (text.matches(".*(取号|候诊|叫号).*")) return visit("取号流程", "候诊与叫号");
        if (text.matches(".*(迟到|提前.*到|到院时间|何时到|什么时候到).*")) return visit("到院时间");
        if (text.matches(".*(就诊.{0,5}(准备|须知|注意)|到医院|到院|就诊.*要|这次.{0,12}(准备|注意)|要准备什么).*")) {
            return visit("到院时间", "证件与凭证", "取号流程");
        }
        return null;
    }

    private static Coverage rule(String... headings) {
        return new Coverage(KnowledgeDocType.APPOINTMENT_RULE, "APPT-RULE-001", List.of(headings));
    }

    private static Coverage visit(String... headings) {
        return new Coverage(KnowledgeDocType.VISIT_NOTICE, "VISIT-001", List.of(headings));
    }
}
