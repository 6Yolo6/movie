package com.gying.movie.service.impl;

import java.util.*;

/** Content features are computed once per catalog; user identities never enter feature vectors. */
public final class CatalogRecommendationEngine {
    private CatalogRecommendationEngine() { }
    public static final String VERSION = "content-features-v2";
    public record Film(String id, String titleCn, String titleEn, Integer year, String category,
                       String posterUrl, String seriesName, List<String> genres, List<String> directors,
                       List<String> actors, double rating, double popularity, int resources,
                       String summary, String aliases, List<String> regions, List<String> languages, Integer runtimeMinutes) {
        public Film(String id, String titleCn, String titleEn, Integer year, String category,
                    String posterUrl, String seriesName, List<String> genres, List<String> directors,
                    List<String> actors, double rating, double popularity, int resources) {
            this(id,titleCn,titleEn,year,category,posterUrl,seriesName,genres,directors,actors,rating,popularity,resources,
                    "","",List.of(),List.of(),null);
        }
        public String title() { return titleCn == null || titleCn.isBlank() ? Objects.toString(titleEn, id) : titleCn; }
    }
    public record Interaction(String user, String movie) { }
    public record Ranked(Film movie, double score, List<String> reasons, Map<String, Double> signals) { }
    public record Constraints(Set<String> genres, String category, Integer year, double minRating,
                              Integer yearFrom, Integer yearTo, Integer maxRuntime, Set<String> excludedGenres,
                              Set<String> directors, Set<String> actors, Set<String> regions, Set<String> languages) {
        public Constraints {
            genres=copy(genres); excludedGenres=copy(excludedGenres); directors=copy(directors);
            actors=copy(actors); regions=copy(regions); languages=copy(languages);
        }
        public Constraints(Set<String> genres,String category,Integer year,double minRating) {
            this(genres,category,year,minRating,null,null,null,Set.of(),Set.of(),Set.of(),Set.of(),Set.of());
        }
        private static Set<String> copy(Set<String> items) { return items==null?Set.of():Set.copyOf(items); }
        public static Constraints none() { return new Constraints(Set.of(),null,null,0); }
        public boolean matches(Film f) {
            return (category==null||category.equals(f.category())) && (year==null||year.equals(f.year()))
                    && (yearFrom==null||f.year()!=null&&f.year()>=yearFrom)
                    && (yearTo==null||f.year()!=null&&f.year()<=yearTo) && f.rating()>=minRating
                    && (maxRuntime==null||f.runtimeMinutes()!=null&&f.runtimeMinutes()<=maxRuntime)
                    && set(f.genres()).containsAll(genres) && Collections.disjoint(set(f.genres()),excludedGenres)
                    && contains(f.directors(),directors) && contains(f.actors(),actors)
                    && contains(f.regions(),regions) && contains(f.languages(),languages);
        }
        private static String nameKey(String value) {
            return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]", "");
        }
        private static boolean contains(List<String> values,Set<String> wanted) {
            return wanted.stream().allMatch(w->values!=null&&values.stream().anyMatch(v->nameKey(v).contains(nameKey(w))));
        }
    }
    private static Set<String> set(List<String> items) { return items == null ? new TreeSet<>() : new TreeSet<>(items); }
    private static double jaccard(Collection<String> left,Collection<String> right) {
        Set<String> a=new HashSet<>(left), b=new HashSet<>(right); if(a.isEmpty()||b.isEmpty())return 0;
        Set<String> union=new HashSet<>(a);union.addAll(b);a.retainAll(b);return (double)a.size()/union.size();
    }
    public static double similarity(Film a,Film b) {
        return .65*jaccard(set(a.genres()),set(b.genres()))+.15*jaccard(set(a.directors()),set(b.directors()))
                +.10*jaccard(set(a.actors()),set(b.actors()))+.10*(Objects.equals(a.category(),b.category())?1:0);
    }
    private static final Map<String,Double> FIELD_WEIGHTS=Map.of("genre",.50,"director",.15,"actor",.10,"category",.10,"decade",.05,"region",.05,"language",.05);
    public record UserFeatures(Map<String,Double> vector,Map<String,Double> seeds,Map<String,Film> sources) {
        public UserFeatures { vector=Map.copyOf(vector);seeds=Map.copyOf(seeds);sources=Map.copyOf(sources); }
        public boolean personalized() { return !seeds.isEmpty(); }
    }
    public static final class FeatureIndex {
        private final List<Film> films;
        private final Map<String,Film> byId;
        private final Map<String,Map<String,Double>> vectors;
        public FeatureIndex(List<Film> catalog) {
            films=List.copyOf(catalog);Map<String,Film> ids=new HashMap<>();Map<String,Double> df=new HashMap<>();
            for(Film f:films) { ids.put(f.id(),f);for(String g:set(f.genres()))df.merge(g,1D,Double::sum); }
            byId=Map.copyOf(ids);Map<String,Map<String,Double>> built=new HashMap<>();
            for(Film f:films) {
                Map<String,Double> vector=new HashMap<>();
                field(vector,"genre",f.genres(),df,films.size());field(vector,"director",f.directors(),Map.of(),0);
                field(vector,"actor",f.actors(),Map.of(),0);field(vector,"region",f.regions(),Map.of(),0);
                field(vector,"language",f.languages(),Map.of(),0);
                field(vector,"category",f.category()==null?List.of():List.of(f.category()),Map.of(),0);
                field(vector,"decade",f.year()==null?List.of():List.of(Integer.toString(f.year()/10)),Map.of(),0);
                built.put(f.id(),Map.copyOf(vector));
            }
            vectors=Map.copyOf(built);
        }
        private static void field(Map<String,Double> target,String name,List<String> values,Map<String,Double> df,int n) {
            Map<String,Double> entries=new HashMap<>();double norm=0;
            for(String value:set(values)) { double w=df.isEmpty()?1:Math.log((n+1D)/(df.getOrDefault(value,0D)+1))+1;entries.put(value,w);norm+=w*w; }
            if(norm==0)return;double denominator=Math.sqrt(norm),scale=Math.sqrt(FIELD_WEIGHTS.get(name));
            entries.forEach((value,w)->target.put(name+":"+value,scale*w/denominator));
        }
        public UserFeatures features(Map<String,Double> profile) {
            Map<String,Double> seeds=new TreeMap<>();profile.forEach((id,w)->{if(byId.containsKey(id)&&w!=null&&Double.isFinite(w)&&w>0)seeds.put(id,w);});
            double total=seeds.values().stream().mapToDouble(x->x).sum();Map<String,Double> vector=new HashMap<>(),support=new HashMap<>();Map<String,Film> sources=new HashMap<>();
            seeds.forEach((id,w)->vectors.get(id).forEach((key,v)-> {
                vector.merge(key,w*v/total,Double::sum);
                if(w*v>support.getOrDefault(key,0D)) {support.put(key,w*v);sources.put(key,byId.get(id));}
            }));
            return new UserFeatures(vector,seeds,sources);
        }
        public List<Ranked> recommend(UserFeatures user,Set<String> excluded,List<Interaction> interactions,Constraints constraints,int limit) {
            Map<String,Double> collaborative=collaborative(user.seeds(),interactions);
            List<Ranked> candidates=new ArrayList<>();
            for(Film f:films) {
                if(excluded.contains(f.id())||!constraints.matches(f))continue;
                double content=0;for(var entry:vectors.get(f.id()).entrySet())content+=entry.getValue()*user.vector().getOrDefault(entry.getKey(),0D);
                double collab=collaborative.getOrDefault(f.id(),0D),quality=Math.max(0,Math.min(1,f.rating()/10));
                double heat=Math.min(1,Math.log1p(Math.max(0,f.popularity()))/Math.log(1001));
                double score=user.personalized()?.55*content+.20*collab+.15*quality+.10*heat:.75*quality+.25*heat;
                List<String> reasons=new ArrayList<>();
                String best=vectors.get(f.id()).keySet().stream().filter(k->k.startsWith("genre:")||k.startsWith("director:")||k.startsWith("actor:"))
                        .filter(user.sources()::containsKey).sorted(Comparator.<String>comparingDouble(k->vectors.get(f.id()).get(k)*user.vector().getOrDefault(k,0D)).reversed().thenComparing(k->k)).findFirst().orElse(null);
                if(best!=null) {
                    Film source=user.sources().get(best);String label=best.substring(best.indexOf(':')+1);
                    reasons.add("与你关注的《"+source.title()+"》"+(best.startsWith("genre:")?"同属"+label:best.startsWith("director:")?"有相同导演："+label:"有相同演员："+label));
                }
                if(collab>0)reasons.add("多位收藏相似影片的用户也收藏了这部");
                if(!constraints.genres().isEmpty())reasons.add("符合本次"+String.join("、",constraints.genres().stream().sorted().toList())+"偏好");
                if(reasons.isEmpty())reasons.add(user.personalized()?"结合你的收藏与近期浏览特征推荐":"根据片库评分与热度推荐；积累收藏后可获得个性化结果");
                if(f.rating()>0)reasons.add("片库评分 "+String.format(Locale.ROOT,"%.1f",f.rating()));
                candidates.add(new Ranked(f,score,List.copyOf(reasons),Map.of("content",content,"collaborative",collab,"quality",quality,"popularity",heat)));
            }
            candidates.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r->r.movie().id()));
            return diversify(candidates,Math.max(1,Math.min(30,limit)));
        }
    }
    /** Aggregate only the neighbors of user's seed items; duplicate user/item pairs add no support. */
    private static Map<String,Double> collaborative(Map<String,Double> seeds,List<Interaction> interactions) {
        Map<String,Set<String>> usersByMovie=new HashMap<>(),moviesByUser=new HashMap<>();
        for(Interaction i:interactions) {usersByMovie.computeIfAbsent(i.movie(),k->new HashSet<>()).add(i.user());moviesByUser.computeIfAbsent(i.user(),k->new HashSet<>()).add(i.movie());}
        Map<String,Double> result=new HashMap<>();double total=seeds.values().stream().mapToDouble(x->x).sum();
        seeds.forEach((seed,weight)-> {
            Set<String> users=usersByMovie.getOrDefault(seed,Set.of());Map<String,Integer> support=new HashMap<>();
            for(String u:users)for(String item:moviesByUser.getOrDefault(u,Set.of()))support.merge(item,1,Integer::sum);
            support.forEach((item,n)->{if(n>=2)result.merge(item,weight/total*n/Math.sqrt((double)users.size()*usersByMovie.get(item).size()),Double::sum);});
        });
        return result;
    }
    public static List<Ranked> recommend(List<Film> catalog,Map<String,Double> profile,Set<String> excluded,List<Interaction> interactions,Constraints constraints,int limit) {
        FeatureIndex index=new FeatureIndex(catalog);return index.recommend(index.features(profile),excluded,interactions,constraints,limit);
    }
    private static List<Ranked> diversify(List<Ranked> input,int limit) {
        List<Ranked> candidates=new ArrayList<>(input.subList(0,Math.min(300,input.size()))),selected=new ArrayList<>();
        while(!candidates.isEmpty()&&selected.size()<limit) {
            Ranked best=null;double value=Double.NEGATIVE_INFINITY;
            for(Ranked candidate:candidates) {
                double redundancy=0;
                for(Ranked old:selected) {
                    double sim=similarity(candidate.movie(),old.movie());String series=candidate.movie().seriesName();
                    if(series!=null&&!series.isBlank()&&series.equals(old.movie().seriesName()))sim=Math.max(sim,.95);
                    redundancy=Math.max(redundancy,sim);
                }
                double diverse=.80*candidate.score()-.20*redundancy;if(diverse>value){value=diverse;best=candidate;}
            }
            selected.add(best);candidates.remove(best);
        }
        return List.copyOf(selected);
    }
}
