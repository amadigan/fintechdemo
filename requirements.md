# Fintech Demo Application

This application will process many Customer instructions & transactions per second (peak
traffic of 100 tps to cover us till end 2027). We would like you to build a Workflow service
which consumes the following customer requests via an endpoint:
1. Register customer
2. Open customer account
3. Create customer deposit transaction (pay-in from our customer)
4. Create customer payment instruction (pay-out to a beneficiary of our customer)
The workflow service should validate and then process those messages in some way
and deliver a response back to a front-end frontend containing the processed
information/results based on the consumed message. Please implement this on AWS in
Java with React Native in mind as the UI technology

Message Consumption
The goal is to expose an endpoint which can consume workflow requests.

Message Processor
The goal is to process messages received from the message consumption endpoint.

Message Frontend
The goal is to render the data from the output of the other two components.
Your options here may include a list of the consumed messages, a graph of processed
data, or a global map with a real-time visualisation. Consider how this hooks up to the
UI React Native code in a way that keeps view and controller code separated.
Here is an example (greatly simplified for convenience so this is not realistic) of the
payment instruction message that will be POSTed (will be assumed by the system during
the code review) to your application:

```json
{"userId": "134256", "currency": "EUR", "amount": 1000,
"transactedAt" : "24-JAN-18 10:27:44", “beneficiaryIBAN”: “”, "originatingCountry" : "FR",
"paymentRef" : "Invoice nr 12345", "purposeRef" : "invoice payment"}
```

How to impress
There are many ways to complete this challenge – none of which
should take you more than a few hours in total. Just because you take an easy
approach won’t set you back in our eyes. Easy might just mean you’re
busy - we understand. Either way, we look for the following:
• Can you write clean, readable, reusable, secure and maintainable code?
• Do you have a good command over OOP and design patterns?
• Have you considered how the automation should be built to allow rapid build,
test, deploy, end to end test, monitoring, alerting. These can be covered in a few
lines each so no major detail required here

• Your approach to writing tests, you don’t need to cover all your code with tests,
just provide a sample and describe your overall approach and how it would be
applied comprehensively

How to submit
Once you’re ready, please share the following three details with our team via email:
1. The endpoint we should POST messages to during our review process
2. The frontend URL we should load to view the output

3. The public GitHub repository where we can review your code (including a one-
page document briefly outlining your approach to the project in the README.md

in the GitHub repo)
4. A short outline (max 500 words – 1 A4 page font size 12) of the approach you
would recommend on developing the UI using React Native with considerations
outlined above

