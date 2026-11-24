package cn.swapy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CatalogQueryTest {
    @TempDir Path temp;
    Catalog.Word word(String id,String text,String form,String role,String status){return new Catalog.Word(id,text,"名词",form,role,"测试",status,1,"");}
    @Test void filtersLiteralKeywordLanguageAndStatusBeforePaging() {
        try(Store db=new Store(temp.resolve("query"))){
            var catalog=new Catalog(db);catalog.importLibrary(new Catalog.Library("swapy-library",1,"query",1,List.of(),List.of(
                word("R_1","Alpha_One","英文标识","标识","启用"),word("R_2","Alpha%Two","英文标识","标识","启用"),
                word("R_3","青溪馆","中文词","设施名","启用"),word("R_4","Alpha_Old","英文标识","标识","弃用"))));
            var query=new CatalogQuery(db);
            var english=query.search(new CatalogQuery.Options("pool","alpha","","","en","启用",1,1));
            assertThat(english.get("total")).isEqualTo(2L);assertThat((List<?>)english.get("items")).hasSize(1);
            var literal=query.search(new CatalogQuery.Options("pool","alpha_","标识","名词","en","启用",1,12));
            assertThat(literal.get("total")).isEqualTo(1L);assertThat(((List<Catalog.Word>)literal.get("items")).get(0).word()).isEqualTo("Alpha_One");
            assertThat(query.search(new CatalogQuery.Options("pool","%","","","all","启用",1,12)).get("total")).isEqualTo(1L);
            assertThat(query.search(new CatalogQuery.Options("pool","","设施名","","zh","启用",1,12)).get("total")).isEqualTo(1L);
            assertThat(query.search(new CatalogQuery.Options("pool","old","","","all","弃用",1,12)).get("total")).isEqualTo(1L);
            assertThat(query.summary().get("poolCount")).isEqualTo(3L);assertThat(query.summary()).doesNotContainKeys("pool","sensitive");
            assertThatThrownBy(()->query.search(new CatalogQuery.Options("pool","","","","all","启用",1,1000))).hasMessageContaining("INVALID_CATALOG_QUERY");
        }
    }
}
