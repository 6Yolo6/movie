package com.gying.movie.service.impl;

import java.util.*;

/** Deterministic content + item co-favorite ranking, with diversity reranking. */
public final class CatalogRecommendationEngine {
    private CatalogRecommendationEngine() { }
    public record Film(String id, String titleCn, String titleEn, Integer year, String category,
                       String posterUrl, String seriesName, List<String> genres, List<String> directors,
                       List<String> actors, double rating, double popularity, int resources) {
        public String title() { return titleCn == null || titleCn.isBlank() ? Objects.toString(titleEn, id) : titleCn; }
    }
    public record Interaction(String user, String movie) { }
    public record Ranked(Film movie, double score, List<String> reasons, Map<String, Double> signals) { }
    public record Constraints(Set<String> genres, String category, Integer year, double minRating) {
        public Constraints { genres=genres==null?Set.of():Set.copyOf(genres); }
        public static Constraints none() { return new Constraints(Set.of(), null, null, 0); }
    }
    private static Set<String> set(List<String> items) { return items == null ? Set.of() : new HashSet<>(items); }
    private static double jaccard(Collection<String> left, Collection<String> right) {
        Set<String> a=new HashSet<>(left), b=new HashSet<>(right);if(a.isEmpty()||b.isEmpty())return 0;
        Set<String> union=new HashSet<>(a);union.addAll(b);a.retainAll(b);return (double)a.size()/union.size();
    }
    public static double similarity(Film a, Film b) {
        return .65*jaccard(set(a.genres()),set(b.genres()))+.15*jaccard(set(a.directors()),set(b.directors()))
                +.10*jaccard(set(a.actors()),set(b.actors()))+.10*(Objects.equals(a.category(),b.category())?1:0);
    }
    public static List<Ranked> recommend(List<Film> catalog, Map<String, Double> profile,
            Set<String> excluded, List<Interaction> interactions, Constraints constraints, int limit) {
        Map<String,Film> byId=new HashMap<>();catalog.forEach(f->byId.put(f.id(),f));
        Map<String,Double> signals=new HashMap<>();profile.forEach((id,w)->{if(byId.containsKey(id)&&w>0)signals.put(id,w);});
        Map<String,Set<String>> users=new HashMap<>();for(Interaction i:interactions)users.computeIfAbsent(i.movie(),k->new HashSet<>()).add(i.user());
        Map<String,Double> idf=new HashMap<>();for(Film f:catalog)for(String genre:set(f.genres()))idf.merge(genre,1D,Double::sum);
        idf.replaceAll((g,n)->Math.log((catalog.size()+1)/(n+1))+1);
        Map<String,Double> preference=new HashMap<>();double weight=signals.values().stream().mapToDouble(x->x).sum();
        for(var signal:signals.entrySet())for(String g:set(byId.get(signal.getKey()).genres()))preference.merge(g,signal.getValue()*idf.getOrDefault(g,1D),Double::sum);
        double preferenceWeight=preference.values().stream().mapToDouble(x->x).sum();
        List<Ranked> candidates=new ArrayList<>();
        for(Film f:catalog) {
            if(excluded.contains(f.id())||constraints.category()!=null&&!constraints.category().equals(f.category())
                    ||constraints.year()!=null&&!constraints.year().equals(f.year())||f.rating()<constraints.minRating()
                    ||!constraints.genres().isEmpty()&&!set(f.genres()).containsAll(constraints.genres()))continue;
            double overlap=0;for(String g:set(f.genres()))overlap+=preference.getOrDefault(g,0D);
            double genreScore=preference.isEmpty()?0:overlap/preferenceWeight;
            Film nearest=null;double best=0,content=0,collab=0;
            for(var signal:signals.entrySet()) {
                Film seed=byId.get(signal.getKey());double similarity=similarity(f,seed);
                content+=signal.getValue()*similarity;
                if(similarity>best){best=similarity;nearest=seed;}
                Set<String> a=users.getOrDefault(seed.id(),Set.of()), b=users.getOrDefault(f.id(),Set.of());
                Set<String> common=new HashSet<>(a);common.retainAll(b);
                if(common.size()>=2&&!a.isEmpty()&&!b.isEmpty())collab+=signal.getValue()*common.size()/Math.sqrt((double)a.size()*b.size());
            }
            content=weight==0?0:.65*content/weight+.35*genreScore;collab=weight==0?0:collab/weight;
            double quality=Math.max(0,Math.min(1,f.rating()/10)),heat=Math.min(1,Math.log1p(Math.max(0,f.popularity()))/Math.log(1001));
            double score=signals.isEmpty()?.75*quality+.25*heat:.55*content+.20*collab+.15*quality+.10*heat;
            List<String> reasons=new ArrayList<>();
            if(nearest!=null&&best>.10) {
                Set<String> shared=set(f.genres());shared.retainAll(set(nearest.genres()));
                if(!shared.isEmpty())reasons.add("与你关注的《"+nearest.title()+"》同属"+String.join("、",shared.stream().sorted().limit(2).toList()));
                else if(!Collections.disjoint(set(f.directors()),set(nearest.directors())))reasons.add("与你关注的《"+nearest.title()+"》有相同导演");
            }
            if(collab>0)reasons.add("多位收藏相似影片的用户也收藏了这部");
            if(!constraints.genres().isEmpty())reasons.add("符合本次"+String.join("、",constraints.genres().stream().sorted().toList())+"偏好");
            if(reasons.isEmpty())reasons.add(signals.isEmpty()?"根据片库评分与热度推荐；积累收藏后可获得个性化结果":"结合你的收藏偏好与片库评分推荐");
            if(f.rating()>0)reasons.add("片库评分 "+String.format(Locale.ROOT,"%.1f",f.rating()));
            candidates.add(new Ranked(f,score,List.copyOf(reasons),Map.of("content",content,"collaborative",collab,"quality",quality,"popularity",heat)));
        }
        candidates.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r->r.movie().id()));
        if(candidates.size()>300)candidates=new ArrayList<>(candidates.subList(0,300));
        List<Ranked> selected=new ArrayList<>();
        while(!candidates.isEmpty()&&selected.size()<limit) {
            Ranked best=null;double value=Double.NEGATIVE_INFINITY;
            for(Ranked candidate:candidates) {
                double redundancy=0;
                for(Ranked old:selected) {
                    double sim=similarity(candidate.movie(),old.movie());
                    String series=candidate.movie().seriesName();
                    if(series!=null&&!series.isBlank()&&series.equals(old.movie().seriesName()))sim=Math.max(sim,.95);
                    redundancy=Math.max(redundancy,sim);
                }
                double diverse=.80*candidate.score()-.20*redundancy;
                if(diverse>value){value=diverse;best=candidate;}
            }
            selected.add(best);candidates.remove(best);
        }
        return List.copyOf(selected);
    }
}
