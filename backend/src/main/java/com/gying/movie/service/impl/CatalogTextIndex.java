package com.gying.movie.service.impl;

import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;
import java.text.Normalizer;
import java.util.*;
import static com.gying.movie.service.impl.CatalogRecommendationEngine.*;

/** Inverted BM25 index with an explicit bilingual concept lexicon; no generated films or scores. */
public final class CatalogTextIndex {
    public static final String VERSION="icu-bm25-concepts-v1";
    private static final Set<String> STOP=Set.of("的","了","和","与","在","是","有","一个","一部","一些","这","那","想","看","找","相关","film","movie","the","a","an","and","of","in","with","to","for","故事","讲述","包含","讲","偏","about");
    private static final List<List<String>> CONCEPTS=List.of(
            List.of("烧脑","时间循环","时间悖论","记忆","梦境","mind-bending"),
            List.of("太空","宇宙","星际","航天","宇航员","space","interstellar"),
            List.of("穿越","时间旅行","时间循环","时空","time travel"),
            List.of("人工智能","机器人","android","artificial intelligence"),
            List.of("末日","灾难","毁灭","apocalypse","disaster"),
            List.of("复仇","报仇","revenge"),List.of("治愈","温暖","温情","heartwarming"),
            List.of("侦探","破案","探案","detective"),List.of("外星人","外星","alien"),
            List.of("成长","青春","coming of age"),List.of("丧尸","僵尸","zombie"),
            List.of("抢劫","盗窃","劫案","heist"),List.of("生存","求生","survival"),
            List.of("悬疑","mystery"),List.of("科幻","sci-fi","science fiction"),
            List.of("爱情","romance"),List.of("喜剧","comedy"),List.of("恐怖","horror")
    );
    public record Hit(Film movie,double score,List<String> reasons,String snippet) { }
    private record Posting(int document,double frequency) { }
    private final List<Film> films;
    private final List<Set<String>> documentTerms;
    private static final ThreadLocal<BreakIterator> WORDS=ThreadLocal.withInitial(()->BreakIterator.getWordInstance(ULocale.CHINESE));
    private final Map<String,List<Posting>> postings;
    private final double[] lengths;
    private final double averageLength;
    public CatalogTextIndex(List<Film> catalog) {
        films=List.copyOf(catalog);List<Set<String>> documentTerms=new ArrayList<>();Map<String,List<Posting>> index=new HashMap<>();lengths=new double[films.size()];double total=0;
        for(int i=0;i<films.size();i++) {
            Film f=films.get(i);Map<String,Double> terms=new HashMap<>();
            add(terms,f.titleCn(),4);add(terms,f.titleEn(),4);add(terms,f.aliases(),3);add(terms,f.seriesName(),3);
            add(terms,String.join(" ",f.genres()),2);add(terms,String.join(" ",f.directors()),2);add(terms,String.join(" ",f.actors()),1.5);add(terms,f.summary(),1);
            documentTerms.add(Set.copyOf(terms.keySet()));
            final int document=i;terms.forEach((term,count)->index.computeIfAbsent(term,k->new ArrayList<>()).add(new Posting(document,count)));
            lengths[i]=terms.values().stream().mapToDouble(x->x).sum();total+=lengths[i];
        }
        Map<String,List<Posting>> immutable=new HashMap<>();index.forEach((k,v)->immutable.put(k,List.copyOf(v)));postings=Map.copyOf(immutable);
        averageLength=films.isEmpty()?1:Math.max(1,total/films.size());this.documentTerms=List.copyOf(documentTerms);
    }
    private static void add(Map<String,Double> target,String text,double weight) { tokens(text).forEach(term->target.merge(term,weight,Double::sum)); }
    public static List<String> tokens(String input) {
        String text=Normalizer.normalize(Objects.toString(input,""),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if(text.length()>6000)text=text.substring(0,6000);
        List<String> result=new ArrayList<>();BreakIterator iterator=WORDS.get();iterator.setText(text);
        int start=iterator.first();for(int end=iterator.next();end!=BreakIterator.DONE;start=end,end=iterator.next()) {
            String token=text.substring(start,end).trim();if(token.codePoints().anyMatch(Character::isLetterOrDigit)&&!STOP.contains(token))result.add(token);
        }
        for(int i=0;i<CONCEPTS.size();i++) {
            boolean found=false;for(String value:CONCEPTS.get(i))if(text.contains(value)){found=true;break;}
            if(found)result.add("concept:"+i);
        }
        return result;
    }
    public List<Hit> search(NaturalMovieQuery.Plan plan) {
        Set<String> terms=new TreeSet<>(tokens(plan.text()));Map<Integer,Double> scores=new HashMap<>();
        for(String term:terms) {
            List<Posting> rows=postings.getOrDefault(term,List.of());double idf=Math.log(1+(films.size()-rows.size()+.5)/(rows.size()+.5));
            for(Posting p:rows) {
                double contribution=idf*p.frequency()*2.2/(p.frequency()+1.2*(.25+.75*lengths[p.document()]/averageLength));
                if(term.startsWith("concept:"))contribution*=.65;
                scores.merge(p.document(),contribution,Double::sum);
            }
        }
        Map<String,Set<String>> conceptCoverage=new HashMap<>();
        for(String term:terms)if(term.startsWith("concept:")) {
            Set<String> aliases=new HashSet<>();for(String phrase:CONCEPTS.get(Integer.parseInt(term.substring(8))))aliases.addAll(tokens(phrase));
            aliases.retainAll(terms);conceptCoverage.put(term,Set.copyOf(aliases));
        }
        List<Hit> result=new ArrayList<>();
        for(int i=0;i<films.size();i++) {
            Film f=films.get(i);if(!plan.constraints().matches(f))continue;
            Set<String> docTerms=documentTerms.get(i);
            Set<String> covered=new HashSet<>(terms);covered.retainAll(docTerms);
            for(var concept:conceptCoverage.entrySet())if(docTerms.contains(concept.getKey()))covered.addAll(concept.getValue());
            if(!terms.isEmpty()&&(scores.getOrDefault(i,0D)==0||covered.size()/(double)terms.size()<.60))continue;
            List<String> reasons=new ArrayList<>();
            List<String> direct=terms.stream().filter(t->!t.startsWith("concept:")&&docTerms.contains(t)).limit(4).toList();
            if(!direct.isEmpty())reasons.add("影片资料命中："+String.join("、",direct));
            for(String term:terms)if(term.startsWith("concept:")&&docTerms.contains(term)) {
                int concept=Integer.parseInt(term.substring(8));String evidence=CONCEPTS.get(concept).stream().filter(v->(Objects.toString(f.summary(),"")+" "+f.title()+" "+Objects.toString(f.aliases(),"")+" "+String.join(" ",f.genres())).toLowerCase(Locale.ROOT).contains(v)).findFirst().orElse("");
                if(!evidence.isBlank())reasons.add("主题匹配："+CONCEPTS.get(concept).get(0)+"（资料包含“"+evidence+"”）");
            }
            if(reasons.isEmpty()&&!terms.isEmpty())reasons.add("影片资料命中本次检索词");
            if(plan.structured())reasons.add("符合本次已识别的筛选条件");
            result.add(new Hit(f,scores.getOrDefault(i,0D),List.copyOf(reasons),snippet(f.summary(),direct)));
        }
        result.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Comparator.comparingDouble((Hit h)->h.movie().rating()).reversed()).thenComparing(h->h.movie().id()));
        return List.copyOf(result);
    }
    /** Candidate recall uses extracted topic text, never unknown conditions or dialogue instructions. */
    public List<Hit> recall(String topic,java.util.function.Predicate<Film> eligible,int limit) {
        Set<String> terms=new TreeSet<>(tokens(topic));
        if(terms.isEmpty())return List.of();
        Map<Integer,Double> scores=new HashMap<>();
        for(String term:terms) {
            List<Posting> rows=postings.getOrDefault(term,List.of());
            double idf=Math.log(1+(films.size()-rows.size()+.5)/(rows.size()+.5));
            for(Posting p:rows) {
                double contribution=idf*p.frequency()*2.2/(p.frequency()+1.2*(.25+.75*lengths[p.document()]/averageLength));
                scores.merge(p.document(),term.startsWith("concept:")?contribution*.65:contribution,Double::sum);
            }
        }
        List<Hit> hits=new ArrayList<>();
        scores.forEach((id,score)->{Film f=films.get(id);if(!eligible.test(f))return;
            List<String> direct=terms.stream().filter(t->!t.startsWith("concept:")&&documentTerms.get(id).contains(t)).limit(4).toList();
            hits.add(new Hit(f,score,direct.isEmpty()?List.of("主题词召回，需核对剧情"):List.of("资料关键词："+String.join("、",direct)),snippet(f.summary(),direct)));
        });
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h->h.movie().id()));
        return List.copyOf(hits.subList(0,Math.min(Math.max(1,limit),hits.size())));
    }
    private static String snippet(String text,List<String> terms) {
        text=Objects.toString(text,"");int position=0;
        for(String term:terms){int p=text.toLowerCase(Locale.ROOT).indexOf(term);if(p>=0){position=Math.max(0,p-35);break;}}
        int end=Math.min(text.length(),position+180);return (position>0?"…":"")+text.substring(position,end)+(end<text.length()?"…":"");
    }
}
