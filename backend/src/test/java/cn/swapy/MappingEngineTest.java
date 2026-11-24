package cn.swapy;

import cn.swapy.matching.Matcher;
import cn.swapy.matching.MappingEngine;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MappingEngineTest {
    @Test void structuredMatchingAllowsSentencePunctuationWithoutSplittingLargerValues() {
        var email=w("S_email","a@example.com","邮箱");var ip=w("S_ip","192.0.2.1","IPv4");var url=w("S_url","https://example.com/","网址");
        assertThat(Matcher.find("Email: a@example.com.",List.of(email))).hasSize(1);
        assertThat(Matcher.find("IP: 192.0.2.1.",List.of(ip))).hasSize(1);
        assertThat(Matcher.find("URL: https://example.com/.",List.of(url))).hasSize(1);
        assertThat(Matcher.find("other.a@example.com.evil 192.0.2.1.5 https://example.com/path",List.of(email,ip,url))).isEmpty();
        assertThat(Matcher.find("时间:00:00。",List.of(w("S_t","00:00","时刻")))).hasSize(1);
    }
    @Test void numbersAndTimesMustNotMatchPartsOfLargerValues() {
        org.junit.jupiter.api.Assertions.assertAll(
            ()->assertThat(Matcher.find("100000.50",List.of(w("S_n","100000","数量")))).isEmpty(),
            ()->assertThat(Matcher.find("00:00:15",List.of(w("S_t","00:00","时刻")))).isEmpty());
    }
    @Test void coarseTypesMatchFacilitiesAndIdentifiersWithoutCrossingOtherTypes() {
        var source=new Catalog.Word("S_h","原医院","名词","中文词","医院名","手填","启用",1,"");
        var facility=new Catalog.Word("R_f","青溪馆","专有名词","中文词","设施名","词库","启用",1,"");
        var person=new Catalog.Word("R_p","赵宁远","专有名词","中文词","人名","词库","启用",1,"");
        var engine=new MappingEngine();
        assertThat(engine.plan(List.of("原医院"),library(List.of(source),List.of(person,facility))).mappings()).extracting(MappingEngine.Mapping::replacement).containsExactly("青溪馆");
        assertThat(WordTypes.role("订单编号","数字代号")).isEqualTo("编号");
        assertThat(WordTypes.role("学校名","中文词")).isEqualTo("设施名");
        assertThat(WordTypes.ROLES).hasSize(10);
    }
    @Test void structuredValuesKeepTheirFormatAndDoNotUseInvalidDates() {
        var source=new Catalog.Word("S_d","2026-01-01","名词","日期","时间","手填","启用",1,"");
        var invalid=new Catalog.Word("R_x","2025-02-30","名词","日期","时间","词库","启用",1,"");
        var wrong=new Catalog.Word("R_w","2001/02/03","名词","日期","时间","词库","启用",1,"");
        var valid=new Catalog.Word("R_d","2001-02-03","名词","日期","时间","词库","启用",1,"");
        var engine=new MappingEngine();var plan=engine.plan(List.of("2026-01-01"),library(List.of(source),List.of(invalid,wrong,valid)));
        assertThat(plan.mappings()).extracting(MappingEngine.Mapping::replacement).containsExactly("2001-02-03");
        assertThat(engine.restore(engine.redact("2026-01-01",plan.mappings(),"B","K_test"),plan.mappings(),"K_test")).isEqualTo("2026-01-01");
    }
    Catalog.Word w(String id,String word,String form) {return new Catalog.Word(id,word,"专有名词",form,"项目代号","用户手填","启用",1,"");}
    Catalog.Library library(List<Catalog.Word> source,List<Catalog.Word> pool) {return new Catalog.Library("swapy-library",1,"test",1,source,pool);}
    @Test void longestOverlapWinsAndEnglishHasWholeWordBoundaries() {
        var words=List.of(w("S1","北京","中文词"),w("S2","北京大学","中文词"),w("S3","Acme","英文缩写"));
        assertThat(Matcher.find("北京大学 ACME MyAcme Acme2 Acme_id 中文Acme公司",words))
                .extracting(h->h.word().id()).containsExactly("S2","S3","S3");
    }
    @Test void naturalAndMarkedOutputsRestoreEachOriginalCase() {
        var engine=new MappingEngine();
        var plan=engine.plan(List.of("ACME Acme aCmE acme"),library(List.of(w("S1","Acme","英文缩写")),List.of(w("R1","Nora","英文缩写"))));
        assertThat(plan.mappings()).hasSize(1);
        String natural=engine.redact("ACME Acme aCmE acme",plan.mappings(),"B","K1");
        assertThat(natural).isEqualTo("NORA Nora nOrA nora");
        assertThat(engine.restore(natural+" Nora",plan.mappings(),"K1")).isEqualTo("ACME Acme aCmE acme Acme");
        String marked=engine.redact("ACME Acme aCmE acme",plan.mappings(),"A","K1");
        assertThat(marked).contains("〔sw:K1:1〕");
        assertThat(engine.restore(marked,plan.mappings(),"K1")).isEqualTo("ACME Acme aCmE acme");
    }
    @Test void candidateInOtherOriginalFileMustBeReselected() {
        var engine=new MappingEngine();
        var plan=engine.plan(List.of("北京", "甲乙已存在"),library(List.of(w("S1","北京","中文词")),List.of(w("R1","甲乙","中文词"),w("R2","林舟","中文词"))));
        assertThat(plan.mappings()).extracting(MappingEngine.Mapping::replacement).containsExactly("林舟");
    }
    @Test void replacementCreatingExtraOverlappingMatchIsRejected() {
        var engine=new MappingEngine();
        var plan=engine.plan(List.of("甲乙北京"),library(List.of(w("S1","北京","中文词")),List.of(w("R1","甲乙甲","中文词"))));
        assertThat(plan.mappings()).isEmpty();
        assertThat(plan.skipped()).containsKey("S1");
    }
    @Test void exhaustedOrIncompatibleAttributesLeaveOriginalAndReport() {
        var engine=new MappingEngine();
        var noPool=engine.plan(List.of("北京"),library(List.of(w("S1","北京","中文词")),List.of()));
        assertThat(engine.redact("北京",noPool.mappings(),"B","K")).isEqualTo("北京");
        assertThat(noPool.skipped()).containsEntry("S1","CANDIDATE_EXHAUSTED");
        var incompatible=engine.plan(List.of("Acme"),library(List.of(w("S1","Acme","英文缩写")),List.of(w("R1","Longer","英文缩写"))));
        assertThat(incompatible.mappings()).isEmpty();
    }
    @Test void replacingDictionaryIdDoesNotAllocateAnotherTargetForAnExistingTaskWord() {
        var engine=new MappingEngine();var original=w("S_z","北京","中文词");var initial=engine.plan(List.of("北京"),library(List.of(original),List.of(w("R1","林舟","中文词"))));
        var updated=new Catalog.Library("swapy-library",1,"test",2,List.of(original.deprecated(),new Catalog.Word("S_a","北京","专有名词","中文词","项目代号","用户手填","启用",2,"")),List.of(w("R1","林舟","中文词"),new Catalog.Word("R2","海津","专有名词","中文词","项目代号","用户手填","启用",2,"")));
        var next=engine.plan(List.of("北京"),updated,initial.mappings());assertThat(next.mappings()).hasSize(1);assertThat(next.mappings().get(0).replacement()).isEqualTo("林舟");
    }
}
