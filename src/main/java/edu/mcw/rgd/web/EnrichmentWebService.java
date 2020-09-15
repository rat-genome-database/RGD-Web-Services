
package edu.mcw.rgd.web;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.GeneDAO;
import edu.mcw.rgd.dao.impl.GeneEnrichmentDAO;
import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.annotation.GeneWrapper;
import edu.mcw.rgd.datamodel.annotation.OntologyEnrichment;
import edu.mcw.rgd.datamodel.annotation.TermWrapper;
import edu.mcw.rgd.datamodel.ontologyx.Aspect;
import edu.mcw.rgd.datamodel.ontologyx.Ontology;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.domain.EnrichmentGeneRequest;
import edu.mcw.rgd.domain.EnrichmentRequest;
import edu.mcw.rgd.process.enrichment.geneOntology.GeneOntologyEnrichmentProcess;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by hsnalabolu on 1/8/2019.
 */
@RestController
@RequestMapping(value = "/enrichment")
public class EnrichmentWebService {

    GeneEnrichmentDAO dao = new GeneEnrichmentDAO();
    GeneDAO gdao = new GeneDAO();
    GeneOntologyEnrichmentProcess process = new GeneOntologyEnrichmentProcess();
    AnnotationDAO adao = new AnnotationDAO();
    OntologyXDAO oDao = new OntologyXDAO();

    @RequestMapping(value = "/data", method = RequestMethod.POST)
    @ApiOperation(value = "Return a chart of ontology terms annotated to the genes.Genes are rgdids separated by comma.Species type is an integer value.Aspect is the Ontology group")
    public Map getEnrichmentData(@RequestBody(required = true) EnrichmentRequest enrichmentRequest)
            throws Exception {

        int speciesTypeKey = SpeciesType.parse(enrichmentRequest.species);
        List<Integer> geneRgdIds = gdao.getActiveGeneRgdIdsBySymbols(enrichmentRequest.genes, speciesTypeKey);
        List<String> termSet = new ArrayList<>();
        ArrayList<String> aspects = new ArrayList<>();
        Ontology ont = oDao.getOntology(enrichmentRequest.aspect);
        String aspect = ont.getAspect();
        if(aspect.equalsIgnoreCase(Aspect.MAMMALIAN_PHENOTYPE) && speciesTypeKey == SpeciesType.HUMAN)
            aspects.add(Aspect.HUMAN_PHENOTYPE); // To get human phenotype for human species
        else aspects.add(aspect);

        int refGenes = dao.getReferenceGeneCount(speciesTypeKey,aspect);
        int inputGenes = geneRgdIds.size();
        Map result = new ConcurrentHashMap();
        List geneData = gdao.getGeneByRgdIds(geneRgdIds);
        List enrichmentData = Collections.synchronizedList(new ArrayList<>());
        LinkedHashMap<String, Integer> geneCounts = adao.getGeneCounts(geneRgdIds, termSet, aspects);

        BigDecimal numberOfTerms = new BigDecimal(geneCounts.keySet().size());
        Iterator tit = geneCounts.keySet().iterator();
        geneCounts.keySet().parallelStream().forEach(i -> {
            try {
                synchronized (result) {
                    ConcurrentHashMap data = new ConcurrentHashMap();
                    String acc = (String) tit.next();
                    String term = oDao.getTermByAccId(acc).getTerm();
                    int refs = geneCounts.get(acc);
                    String pvalue = process.calculatePValue(inputGenes, refGenes, acc, refs, speciesTypeKey);
                    String bonferroni = process.calculateBonferroni(pvalue, numberOfTerms);

                    data.put("acc", acc);
                    data.put("term", term);
                    data.put("count", refs);
                    data.put("pvalue", pvalue);
                    data.put("correctedpvalue", bonferroni);
                    enrichmentData.add(data);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Collections.sort(enrichmentData, new SortbyPvalue());
        result.put("enrichment",enrichmentData);
        result.put("geneSymbols",geneData);
        return result;
    }

    @RequestMapping(value = "/annotatedGenes", method = RequestMethod.POST)
    @ApiOperation(value = "Return a list of genes annotated to the term.Genes are rgdids separated by comma.Species type is an integer value.term is the ontology")
    public Map getEnrichmentData(@RequestBody(required = true) EnrichmentGeneRequest geneRequest)
            throws Exception {

        int speciesTypeKey = SpeciesType.parse(geneRequest.species);
        Map result = new ConcurrentHashMap();
        List geneData = Collections.synchronizedList(new ArrayList<>());
        List genes = Collections.synchronizedList(new ArrayList<>());

        List<Integer> geneRgdIds = gdao.getActiveGeneRgdIdsBySymbols(geneRequest.geneSymbols, speciesTypeKey);
        List<String> termSet = new ArrayList<>();
        termSet.add(geneRequest.accId);
        ArrayList<String> aspects = new ArrayList<>();
        OntologyEnrichment oe = adao.getOntologyEnrichment(geneRgdIds, termSet, aspects);
        TermWrapper tw = null;
        tw = (TermWrapper) oe.termMap.get(geneRequest.accId);
        Iterator git = tw.refs.iterator();
        boolean first = true;
        while (git.hasNext()) {
            ConcurrentHashMap data = new ConcurrentHashMap();
            GeneWrapper gw = (GeneWrapper) git.next();
            Iterator bIt = gw.getRoots(tw).iterator();
            List<String> terms = Collections.synchronizedList(new ArrayList<>());
            while (bIt.hasNext()) {
                Term baseTerm = (Term) bIt.next();
                terms.add(baseTerm.getTerm());
            }
            if(terms.size() == 0)
                terms.add((oDao.getTermByAccId(geneRequest.accId)).getTerm());

            data.put("gene", gw.getGene().getSymbol());
            data.put("terms", terms);
            geneData.add(data);
            genes.add(gw.getGene().getSymbol());
        }

        result.put("geneData", geneData);
        result.put("genes", genes);
        return result;
    }


}

class SortbyPvalue implements Comparator<ConcurrentHashMap> {

    public int compare(ConcurrentHashMap s1, ConcurrentHashMap s2) {
        if (new BigDecimal((String)(s1.get("pvalue"))).compareTo(new BigDecimal((String)( s2.get("pvalue")))) == 0)
            return Integer.compare(((int) s2.get("count")), ((int) s1.get("count")));
        else
            return (new BigDecimal((String)(s1.get("pvalue"))).compareTo(new BigDecimal((String)( s2.get("pvalue")))));
    }
}

