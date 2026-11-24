package cn.swapy;

import cn.swapy.matching.Matcher;
import cn.swapy.matching.MappingEngine;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MappingEngineTest {
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
