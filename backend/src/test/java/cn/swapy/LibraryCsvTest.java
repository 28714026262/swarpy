package cn.swapy;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class LibraryCsvTest {
    @Test void sourceNoticesTravelWithTheSingleCsv() {
        var word=new Catalog.Word("R_1","revise","动词","英文词","通用词","WordNet:3.0","启用",1,"");
        var library=new Catalog.Library("swapy-library",1,"test",1,List.of(),List.of(word));
        byte[] data=LibraryCsv.encode(library);
        assertThat(new String(data,StandardCharsets.UTF_8)).contains("license,WordNet-3.0,").contains("WordNet 3.0 Copyright");
        assertThat(LibraryCsv.decode(data)).isEqualTo(library);
    }
    @Test void existingLargeMetadataStillRoundTrips() {
        var word=new Catalog.Word("R_1","林舟","名词","中文词","事物名","来源","启用",1,"x".repeat(4097));
        var library=new Catalog.Library("swapy-library",1,"test",1,List.of(),List.of(word));
        assertThat(LibraryCsv.decode(LibraryCsv.encode(library))).isEqualTo(library);
    }
    @Test void csvPreservesAllFieldsIncludingQuotedMultilineText() {
        var source=new Catalog.Word("S_1","甲,乙\"丙\n丁","名词","中文词","事物名","手填","启用",1,null);
        var target=new Catalog.Word("R_1","林舟","专有名词","中文词","事物名","词库","弃用",2,"\\N");
        var library=new Catalog.Library("swapy-library",1,"test",2,List.of(source),List.of(target));
        byte[] csv=LibraryCsv.encode(library);
        assertThat(new String(csv,StandardCharsets.UTF_8)).startsWith("swapy-library-csv,1,test,2\n").contains("kind,id,word,pos,form,role");
        assertThat(LibraryCsv.decode(csv)).isEqualTo(library);
    }
    @Test void bomAndCrLfAreAcceptedButUnknownKindsAndBrokenQuotesFail() {
        String head="\uFEFFswapy-library-csv,1,test,1\r\nkind,id,word,pos,form,role,source,status,introducedVersion,family\r\n";
        assertThat(LibraryCsv.decode(head.getBytes(StandardCharsets.UTF_8)).pool()).isEmpty();
        assertThatThrownBy(()->LibraryCsv.decode((head+"other,R_1,林舟,名词,中文词,人名,词库,启用,1,\r\n").getBytes(StandardCharsets.UTF_8))).hasMessageContaining("INVALID_LIBRARY_CSV");
        assertThatThrownBy(()->LibraryCsv.decode((head+"pool,R_1,\"unterminated").getBytes(StandardCharsets.UTF_8))).hasMessageContaining("INVALID_LIBRARY_CSV");
    }
}
