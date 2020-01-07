package datawave.query;

import datawave.query.testframework.AbstractFunctionalQuery;
import datawave.query.testframework.AccumuloSetupHelper;
import datawave.query.testframework.CitiesDataType;
import datawave.query.testframework.CitiesDataType.CityEntry;
import datawave.query.testframework.CitiesDataType.CityField;
import datawave.query.testframework.GenericCityFields;
import datawave.query.testframework.DataTypeHadoopConfig;
import datawave.query.testframework.FieldConfig;
import org.apache.log4j.Logger;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;

import static datawave.query.testframework.RawDataManager.AND_OP;
import static datawave.query.testframework.RawDataManager.EQ_OP;
import static datawave.query.testframework.RawDataManager.GTE_OP;
import static datawave.query.testframework.RawDataManager.GT_OP;
import static datawave.query.testframework.RawDataManager.LTE_OP;
import static datawave.query.testframework.RawDataManager.LT_OP;
import static datawave.query.testframework.RawDataManager.NE_OP;
import static datawave.query.testframework.RawDataManager.OR_OP;

public class JexlNumericQueryTest extends AbstractFunctionalQuery {
    
    private static final Logger log = Logger.getLogger(JexlNumericQueryTest.class);
    
    @BeforeClass
    public static void filterSetup() throws Exception {
        Collection<DataTypeHadoopConfig> dataTypes = new ArrayList<>();
        FieldConfig generic = new GenericCityFields();
        generic.addIndexField(CityField.NUM.name());
        dataTypes.add(new CitiesDataType(CityEntry.generic, generic));
        
        final AccumuloSetupHelper helper = new AccumuloSetupHelper(dataTypes);
        client = helper.loadTables(log);
    }
    
    public JexlNumericQueryTest() {
        super(CitiesDataType.getManager());
    }
    
    // ============================================
    // unit tests
    
    @Test
    public void testNumInQuotes() throws Exception {
        log.info("------  testNumInQuotes  ------");
        for (TestCities city : TestCities.values()) {
            String query = CityField.NUM.name() + EQ_OP + "'110'" + AND_OP + CityField.CITY.name() + NE_OP + "'" + city.name() + "'";
            String expect = CityField.NUM.name() + EQ_OP + "110" + AND_OP + CityField.CITY.name() + NE_OP + "'" + city.name() + "'";
            runTest(query, expect);
        }
    }
    
    @Test
    public void testNumWithoutQuotes() throws Exception {
        log.info("------  testNumWithoutQuotes  ------");
        for (TestCities city : TestCities.values()) {
            String query = CityField.NUM.name() + EQ_OP + "110" + AND_OP + CityField.CITY.name() + NE_OP + "'" + city.name() + "'";
            runTest(query, query);
        }
    }
    
    @Test
    public void testNumWithoutQuotesAndNot() throws Exception {
        log.info("------  testNumWithoutQuotesAndNot  ------");
        for (TestCities city : TestCities.values()) {
            String query = CityField.NUM.name() + EQ_OP + "110" + AND_OP + "not (" + CityField.CITY.name() + EQ_OP + "'" + city.name() + "')";
            runTest(query, query);
        }
    }
    
    @Test
    public void testLteGteBound() throws Exception {
        log.info("------  testLteGteBound  ------");
        String query = CityField.NUM.name() + LTE_OP + "20 " + AND_OP + CityField.NUM.name() + GTE_OP + "20";
        runTest(query, query);
    }
    
    @Test
    public void testGteLteBound() throws Exception {
        log.info("------  testGteLteBound  ------");
        String query = CityField.NUM.name() + GTE_OP + "20 " + AND_OP + CityField.NUM.name() + LTE_OP + "40";
        runTest(query, query);
    }
    
    @Test
    public void testGtLtBound() throws Exception {
        log.info("------  testGtLtBound  ------");
        String query = CityField.NUM.name() + GT_OP + "24 " + AND_OP + CityField.NUM.name() + LT_OP + "105";
        runTest(query, query);
    }
    
    @Test
    public void testMultiBound() throws Exception {
        log.info("------  testMultiBound  ------");
        String query = "(" + CityField.NUM.name() + GT_OP + "15 " + AND_OP + CityField.NUM.name() + LT_OP + "24)" + OR_OP + "(" + CityField.NUM.name() + GT_OP
                        + "31 and " + CityField.NUM.name() + LT_OP + "42)";
        runTest(query, query);
    }
    
    @Test
    public void testAnd() throws Exception {
        log.info("------  testAnd  ------");
        String val = "100";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.NUM.name() + EQ_OP + "" + val + AND_OP + CityField.CITY.name() + EQ_OP + "'" + city.name() + "'";
            runTest(query, query);
        }
    }
    
    @Test
    public void testOr() throws Exception {
        log.info("------  testOr  ------");
        String val = "30";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.NUM.name() + EQ_OP + "" + val + OR_OP + CityField.CITY.name() + EQ_OP + "'" + city.name() + "'";
            runTest(query, query);
        }
    }
    
    @Test
    public void testOrMulti() throws Exception {
        log.info("------  testOrMulti  ------");
        for (final TestCities city : TestCities.values()) {
            String query = "(" + CityField.NUM.name() + EQ_OP + "100 " + OR_OP + CityField.NUM.name() + EQ_OP + "110 " + OR_OP + CityField.NUM.name() + EQ_OP
                            + "120 " + OR_OP + CityField.NUM.name() + EQ_OP + "130 " + ") and " + CityField.CITY.name() + EQ_OP + "'" + city.name() + "'";
            runTest(query, query);
        }
    }
    
    @Test
    public void testAndGteLte() throws Exception {
        log.info("------  testAndGteLte  ------");
        for (final TestCities city : TestCities.values()) {
            String query = "(" + CityField.NUM.name() + GTE_OP + "100 " + AND_OP + CityField.NUM.name() + LTE_OP + "130 " + ") and " + CityField.CITY.name()
                            + EQ_OP + "'" + city.name() + "'";
            runTest(query, query);
        }
    }
    
    @Test
    public void testOrGtLt() throws Exception {
        log.info("------  testOrGtLt  ------");
        for (final TestCities city : TestCities.values()) {
            String query = "(" + CityField.NUM.name() + LT_OP + "100 " + OR_OP + CityField.NUM.name() + GT_OP + "110 " + ") and " + CityField.CITY.name()
                            + EQ_OP + "'" + city.name() + "'";
            runTest(query, query);
        }
    }
    
    @Test
    public void testOrNotEq() throws Exception {
        log.info("------  testOrNotEq  ------");
        for (final TestCities city : TestCities.values()) {
            String query = "(" + CityField.NUM.name() + NE_OP + "100 " + OR_OP + CityField.NUM.name() + NE_OP + "110 " + ") and " + CityField.CITY.name()
                            + EQ_OP + "'" + city.name() + "'";
            runTest(query, query);
        }
    }
    
    @Test
    public void testLtGtNotEq() throws Exception {
        log.info("------  testLtGtNotEq  ------");
        for (final TestCities city : TestCities.values()) {
            String query = "(" + CityField.NUM.name() + GT_OP + "99 " + AND_OP + CityField.NUM.name() + LT_OP + "121 " + AND_OP + CityField.NUM.name() + NE_OP
                            + "110 " + ") and " + CityField.CITY.name() + EQ_OP + "'" + city.name() + "'";
            runTest(query, query);
        }
    }
    
    // ============================================
    // implemented abstract methods
    protected void testInit() {
        this.auths = CitiesDataType.getTestAuths();
        this.documentKey = CityField.EVENT_ID.name();
    }
    
    // ============================================
    // private methods
}