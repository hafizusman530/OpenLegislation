package gov.nysenate.openleg.processor.bill.sponsor;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import gov.nysenate.openleg.model.base.SessionYear;
import gov.nysenate.openleg.model.bill.Bill;
import gov.nysenate.openleg.model.bill.BillAmendment;
import gov.nysenate.openleg.model.bill.BillId;
import gov.nysenate.openleg.model.bill.BillSponsor;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.entity.SessionMember;
import gov.nysenate.openleg.model.process.DataProcessUnit;
import gov.nysenate.openleg.model.sobi.SobiFragment;
import gov.nysenate.openleg.model.sobi.SobiFragmentType;
import gov.nysenate.openleg.processor.base.AbstractDataProcessor;
import gov.nysenate.openleg.processor.base.ParseError;
import gov.nysenate.openleg.processor.sobi.SobiProcessor;
import gov.nysenate.openleg.util.XmlHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by robert on 2/22/17.
 */
@Service
public class SponsorSobiProcessor extends AbstractDataProcessor implements SobiProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SponsorSobiProcessor.class);
    @Autowired
    private XmlHelper xmlHelper;

    public SponsorSobiProcessor() {
    }

    @Override
    public void init() {
        initBase();
    }

    @Override
    public SobiFragmentType getSupportedType() {
        return SobiFragmentType.LDSPON;
    }

    @Override
    public void process(SobiFragment sobiFragment) {
        logger.info("Processing Sponsor...");
        LocalDateTime date = sobiFragment.getPublishedDateTime();
        logger.info("Processing " + sobiFragment.getFragmentId() + " (xml file).");
        DataProcessUnit unit = createProcessUnit(sobiFragment);
        try {
            final Document doc = xmlHelper.parse(sobiFragment.getText());
            final Node billTextNode = xmlHelper.getNode("sponsor_data", doc);
            final Integer sessyr = xmlHelper.getInteger("@sessyr", billTextNode);
            final String sponsorhse = xmlHelper.getString("@billhse", billTextNode).trim();
            final Integer sponsorno = xmlHelper.getInteger("@billno", billTextNode);
            final String action = xmlHelper.getString("@action", billTextNode).trim(); // TODO: implement actions
            final String prime = xmlHelper.getString("prime", billTextNode).trim();
            final String coprime = xmlHelper.getString("co-prime", billTextNode).trim();
            final String multi = xmlHelper.getString("multi", billTextNode).trim();

            Bill baseBill = getOrCreateBaseBill(sobiFragment.getPublishedDateTime(), new BillId(sponsorhse +
                    sponsorno, sessyr), sobiFragment);
            BillSponsor billSponsor = baseBill.getSponsor();
            Chamber chamber = baseBill.getBillType().getChamber();
            BillAmendment amendment = baseBill.getAmendment(baseBill.getActiveVersion());

            if (action.equals("remove")) {
                removeProcess(amendment, baseBill);
            } else {
                Pattern rulesSponsorPattern =
                        Pattern.compile("(RULES (?:COM ))|(BUDGET BILL)?\\(?([a-zA-Z-' ]+)\\)?(.*)");
                Matcher rules = rulesSponsorPattern.matcher(prime);
                rules.find();
                if (rules.group().contains("BUDGET BILL")) {
                    budgetSponsorProcess(baseBill);
                    billSponsor.setBudget(true);
                } else {
                    String sponsor;
                    if (rules.group().contains("RULES ")) {
                        rules.find();
                        sponsor = rules.group().trim();
                        billSponsor.setRules(true);
                    } else {
                        sponsor = prime;
                    }
                    List<SessionMember> sessionMembers = getSessionMember(sponsor, baseBill.getSession(), chamber);
                    billSponsor.setMember(sessionMembers.get(0));
                    amendment.setCoSponsors(getSessionMember(coprime, baseBill.getSession(), chamber));
                    amendment.setMultiSponsors(getSessionMember(multi, baseBill.getSession(), chamber));
                }
            }
        } catch (IOException | SAXException | XPathExpressionException e) {
            throw new ParseError("Error While Parsing AnActXML", e);
        }
    }

    public void removeProcess(BillAmendment amendment, Bill bill) {
        bill.setSponsor(null);
        List<SessionMember> empty1 = new ArrayList<>();
        amendment.setCoSponsors(empty1);
        List<SessionMember> empty2 = new ArrayList<>();
        amendment.setMultiSponsors(empty2);
    }

    public void budgetSponsorProcess(Bill baseBill) {
        BillSponsor billSponsor = new BillSponsor();
        billSponsor.setBudget(true);
    }

    public List<SessionMember> getSessionMember(String sponsors, SessionYear session, Chamber chamber) {
        List<String> shortNames = Lists.newArrayList(
                Splitter.on(",").omitEmptyStrings().trimResults().splitToList(sponsors.toUpperCase()));
        List<SessionMember> sessionMembers = new ArrayList<>();
        for (String t : shortNames) {
            sessionMembers.add(getMemberFromShortName(t, session, chamber));
        }
        return sessionMembers;
    }

    @Override
    public void postProcess() {
        flushBillUpdates();
    }
}