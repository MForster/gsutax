package pro.forster.gsutax;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Money;

class TaxEvent {
  private ImmutableList<Transaction> deliveries;
  private Transaction sale;
  private Optional<Transaction> transfer;

  TaxEvent(List<Transaction> deliveries, Transaction sale, Optional<Transaction> transfer) {
    this.deliveries = ImmutableList.copyOf(deliveries);
    this.sale = checkNotNull(sale);
    this.transfer = checkNotNull(transfer);

    checkArgument(sale.getShares() == deliveries.stream().mapToLong(Transaction::getShares).sum(),
        "Sale doesn't match deliveries: %s %", sale, deliveries);
    checkArgument(!deliveries.isEmpty(),
        "Didn't find deliveries for sale: %s", sale);
    checkArgument(transfer.isPresent() || sale.getCurrencyCode().equals("EUR"),
        "Missing transfer of sale: %s", sale);
    checkArgument(transfer.isEmpty() || !sale.getCurrencyCode().equals("EUR"),
        "Transfer of EUR sale: %s %s", sale, transfer);
  }

  ImmutableList<Transaction> getDeliveries() {
    return deliveries;
  }

  Transaction getSale() {
    return sale;
  }

  Optional<Transaction> getTransfer() {
    return transfer;
  }

  Money getMonetaryAmount() {
    return getTransfer().orElse(sale).getMonetaryAmount();
  }

  int getYear() {
    return getTransfer().orElse(sale).getDateTime().getYear();
  }

  Money getTotalDeliveryEurAmount(CurrencyConverter converter) {
    return deliveries.stream()
        .map(d -> converter.convert(d.getDateTime(), d.getMonetaryAmount()))
        .reduce(Money::add).get();
  }

  Money getProfit(CurrencyConverter converter) {
    return getMonetaryAmount().subtract(getTotalDeliveryEurAmount(converter));
  }

  @Override
  public String toString() {
    var b = new StringBuilder();

    for (var d : deliveries) {
      b.append(d).append(' ').append(d.getShares() / 100000000.0).append('\n');
    }

    b.append(sale).append(' ').append(sale.getShares() / 100000000.0).append('\n');
    b.append(getTransfer()).append('\n');

    return b.toString();
  }
}
