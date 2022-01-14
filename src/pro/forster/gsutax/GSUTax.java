package pro.forster.gsutax;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.model.PortfolioTransaction;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

class GSUTax {

  static List<Transaction> collectTransactions(Client client) {
    return Stream.concat(
        client.getPortfolios().stream()
            .filter(p -> p.getName().equals("Morgan Stanley")).findAny().get()
            .getTransactions().stream()
            .filter(t -> !t.getDateTime().isBefore(LocalDateTime.of(2018, Month.DECEMBER, 1, 0, 0))),
        client.getAccounts().stream()
            .filter(a -> a.getName().equals("OFX")).findAny().get()
            .getTransactions().stream()
            .map(t -> t.getCrossEntry().getCrossTransaction(t))
            .filter(t -> t instanceof AccountTransaction))
        .sorted(new Transaction.ByDate())
        .collect(toList());
  }

  static List<TaxEvent> createTaxEvents(List<Transaction> transactions) {
    var events = new ArrayList<TaxEvent>();

    var deliveries = new ImmutableList.Builder<Transaction>();
    var sale = Optional.<Transaction>empty();
    var transfer = Optional.<Transaction>empty();

    for (var t : transactions) {
      if (t instanceof PortfolioTransaction) {
        if (((PortfolioTransaction) t).getType() == PortfolioTransaction.Type.DELIVERY_INBOUND) {
          deliveries.add(t);
        } else {
          if (sale.isPresent()) {
            throw new AssertionError("Found duplicate sale: \n" + sale + t);
          }
          sale = Optional.of(t);
        }
      } else {
        transfer = Optional.of(t);
      }

      if (transfer.isPresent() || (sale.isPresent() && sale.get().getCurrencyCode().equals("EUR"))) {
        events.add(new TaxEvent(deliveries.build(), sale.get(), transfer));
        deliveries = new ImmutableList.Builder<>();
        sale = Optional.empty();
        transfer = Optional.empty();
      }
    }

    return events;
  }

  static CurrencyConverter getCurrencyConverter(Client client) throws IOException {
    ExchangeRateProviderFactory factory = new ExchangeRateProviderFactory(client);
    for (var p : ExchangeRateProviderFactory.getProviders()) {
      p.update(null);
    }
    return new CurrencyConverterImpl(factory, "EUR");
  }

  static void analyzeTaxEvents(List<TaxEvent> events, CurrencyConverter converter) {
    for (var entry : events.stream().collect(groupingBy(TaxEvent::getYear)).entrySet()) {
      var year = entry.getKey();
      var yearlyEvents = entry.getValue();

      System.out.printf("\n================== %d\n\n", year);

      for (var e : yearlyEvents) {
        for (var d : e.getDeliveries()) {
          System.out.printf("Einlieferung: %s %8.4f %s (%s, %6.4f USD/EUR)\n",
              d.getDateTime().format(ISO_LOCAL_DATE),
              d.getShares() / 100000000.,
              converter.convert(d.getDateTime(), d.getMonetaryAmount()),
              d.getMonetaryAmount(),
              converter.getRate(d.getDateTime(), d.getMonetaryAmount().getCurrencyCode()).getValue().floatValue());
        }
        System.out.printf("Einstandswert gesamt:             %s\n", e.getTotalDeliveryEurAmount(converter));

        System.out.printf("Verkauf:      %s %8.4f %s\n",
            e.getSale().getDateTime().format(ISO_LOCAL_DATE),
            e.getSale().getShares() / 100000000.,
            e.getMonetaryAmount());
        System.out.printf("Gewinn/Verlust:                   %s\n", e.getProfit(converter));
        System.out.println();
      }

      System.out.printf("Gesamt: %s\n", entry.getValue().stream()
          .map(e -> e.getProfit(converter))
          .reduce(Money::add).get());
    }
  }

  public static void main(String[] args) throws IOException {
    FileInputStream input = new FileInputStream("/home/forster/data/Dokumente/Finanzen/Portfolio.xml");
    var client = ClientFactory.load(input);
    var transactions = collectTransactions(client);
    var events = createTaxEvents(transactions);
    var converter = getCurrencyConverter(client);
    analyzeTaxEvents(events, converter);
  }
}
