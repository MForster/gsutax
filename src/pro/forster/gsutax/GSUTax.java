package pro.forster.gsutax;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
  static class TaxEvent {
    List<PortfolioTransaction> deliveries = new ArrayList<>();
    PortfolioTransaction sale;
    AccountTransaction transfer;

    void validate() {
      if (sale.getShares() != deliveries.stream().mapToLong(PortfolioTransaction::getShares).sum()) {
        throw new AssertionError("Sale doesn't match deliveries: \n" + this.toString());
      }
      if (deliveries.isEmpty()) {
        throw new AssertionError("Didn't find deliveries for sale: \n" + this.toString());
      }
    }

    int getYear() {
      return transfer.getDateTime().getYear();
    }

    Money getTotalDeliveryEurAmount(CurrencyConverter converter) {
      return deliveries.stream()
          .map(d -> converter.convert(d.getDateTime(), d.getMonetaryAmount()))
          .reduce(Money::add).get();
    }

    Money getProfit(CurrencyConverter converter) {
      return transfer.getMonetaryAmount().subtract(getTotalDeliveryEurAmount(converter));
    }

    @Override
    public String toString() {

      var b = new StringBuilder();
      for (var d : deliveries) {
        b.append(d).append(' ').append(d.getShares() / 100000000.0).append('\n');
      }
      b.append(sale).append(' ').append(sale.getShares() / 100000000.0).append('\n');
      b.append(transfer).append('\n');

      return b.toString();
    }
  }

  static List<Transaction> collectTransactions(Client client) {
    return Stream.concat(
        client.getPortfolios().stream()
            .filter(p -> p.getName().equals("Morgan Stanley")).findAny().get()
            .getTransactions().stream()
            .filter(t -> t.getType() == PortfolioTransaction.Type.DELIVERY_INBOUND)
            .filter(t -> !t.getDateTime().isBefore(LocalDateTime.of(2019, Month.MARCH, 1, 0, 0))),
        client.getAccounts().stream()
            .filter(a -> a.getName().equals("OFX")).findAny().get()
            .getTransactions().stream()
            .map(t -> t.getCrossEntry().getCrossTransaction(t)))
        .sorted(new Transaction.ByDate())
        .collect(toList());
  }

  static List<TaxEvent> createTaxEvents(List<Transaction> transactions) {
    var events = new ArrayList<TaxEvent>();
    var event = new TaxEvent();

    for (var t : transactions) {
      if (t instanceof PortfolioTransaction) {
        var pt = (PortfolioTransaction) t;
        if (pt.getType() == PortfolioTransaction.Type.DELIVERY_INBOUND) {
          event.deliveries.add(pt);
        } else {
          event.sale = pt;
        }
      } else {
        event.transfer = (AccountTransaction) t;
        event.validate();
        events.add(event);
        event = new TaxEvent();
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
        for (var d : e.deliveries) {
          System.out.printf("Einlieferung: %s %8.4f %s (%s, %6.4f USD/EUR)\n",
              d.getDateTime().format(ISO_LOCAL_DATE),
              d.getShares() / 100000000.,
              converter.convert(d.getDateTime(), d.getMonetaryAmount()),
              d.getMonetaryAmount(),
              converter.getRate(d.getDateTime(), d.getMonetaryAmount().getCurrencyCode()).getValue().floatValue());
        }
        System.out.printf("Einstandswert gesamt:             %s\n", e.getTotalDeliveryEurAmount(converter));

        System.out.printf("Verkauf:      %s %8.4f %s\n",
            e.sale.getDateTime().format(ISO_LOCAL_DATE),
            e.sale.getShares() / 100000000.,
            e.transfer.getMonetaryAmount());
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
