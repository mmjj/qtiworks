<%--

Copyright (c) 2012-2013, The University of Edinburgh.
All Rights Reserved

Form for editing existing Test Delivery settings

Model:

testDeliverySettingsTemplate - form backing template

--%>
<%@ include file="/WEB-INF/jsp/includes/pageheader.jspf" %>
<page:page title="View/Edit Test Delivery Settings">

  <header class="actionHeader">
    <nav class="breadcrumbs">
      <a href="${utils:escapeLink(primaryRouting['dashboard'])}">QTIWorks Dashboard</a> &#xbb;
      <a href="${utils:escapeLink(primaryRouting['deliverySettingsManager'])}">Delivery Settings Manager</a> &#xbb;
    </nav>
    <h2>
      <a href="${utils:escapeLink(primaryRouting['listTestDeliverySettings'])}">Test Delivery Settings</a> &#xbb;
      ${fn:escapeXml(deliverySettings.title)}
    </h2>
    <div class="hints">
      <p>
        The current values for these settings are shown below. You can make changes to these if required.
        below.
      </p>
    </div>
  </header>
  <%@ include file="/WEB-INF/jsp/includes/instructor/testDeliverySettingsForm.jspf" %>

  <p class="floatRight">
    <a href="${utils:escapeLink(primaryRouting['listTestDeliverySettings'])}">Cancel and return to Test Delivery Settings list</a>
  </p>

</page:page>

