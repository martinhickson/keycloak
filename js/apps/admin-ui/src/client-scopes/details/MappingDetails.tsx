import type ProtocolMapperRepresentation from "@keycloak/keycloak-admin-client/lib/defs/protocolMapperRepresentation";
import type { ProtocolMapperTypeRepresentation } from "@keycloak/keycloak-admin-client/lib/defs/serverInfoRepesentation";
import {
  ActionGroup,
  AlertVariant,
  Button,
  ButtonVariant,
  DropdownItem,
  FormGroup,
  PageSection,
  ValidatedOptions,
} from "@patternfly/react-core";
import { useState } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { Link, useMatch, useNavigate } from "react-router-dom";
import { HelpItem } from "ui-shared";
import { adminClient } from "../../admin-client";
import { useAlerts } from "../../components/alert/Alerts";
import { useConfirmDialog } from "../../components/confirm-dialog/ConfirmDialog";
import { DynamicComponents } from "../../components/dynamic/DynamicComponents";
import { FormAccess } from "../../components/form/FormAccess";
import { KeycloakTextInput } from "../../components/keycloak-text-input/KeycloakTextInput";
import { ViewHeader } from "../../components/view-header/ViewHeader";
import { useRealm } from "../../context/realm-context/RealmContext";
import { useServerInfo } from "../../context/server-info/ServerInfoProvider";
import { convertFormValuesToObject, convertToFormValues } from "../../util";
import { useFetch } from "../../utils/useFetch";
import { useParams } from "../../utils/useParams";
import { toClientScope } from "../routes/ClientScope";
import { MapperParams, MapperRoute } from "../routes/Mapper";
import { toDedicatedScope } from "../../clients/routes/DedicatedScopeDetails";

export default function MappingDetails() {
  const { t } = useTranslation("client-scopes");
  const { addAlert, addError } = useAlerts();

  const { id, mapperId } = useParams<MapperParams>();
  const form = useForm();
  const {
    register,
    setValue,
    formState: { errors },
    handleSubmit,
  } = form;
  const [mapping, setMapping] = useState<ProtocolMapperTypeRepresentation>();
  const [config, setConfig] = useState<{
    protocol?: string;
    protocolMapper?: string;
  }>();

  const navigate = useNavigate();
  const { realm } = useRealm();
  const serverInfo = useServerInfo();
  const isGuid = /^[{]?[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}[}]?$/;
  const isUpdating = !!mapperId.match(isGuid);

  const isOnClientScope = !!useMatch(MapperRoute.path);
  const toDetails = () =>
    isOnClientScope
      ? toClientScope({ realm, id, tab: "mappers" })
      : toDedicatedScope({ realm, clientId: id, tab: "mappers" });

  useFetch(
    async () => {
      let data: ProtocolMapperRepresentation | undefined;
      if (isUpdating) {
        if (isOnClientScope) {
          data = await adminClient.clientScopes.findProtocolMapper({
            id,
            mapperId,
          });
        } else {
          data = await adminClient.clients.findProtocolMapperById({
            id,
            mapperId,
          });
        }
        if (!data) {
          throw new Error(t("common:notFound"));
        }

        const mapperTypes = serverInfo.protocolMapperTypes![data!.protocol!];
        const mapping = mapperTypes.find(
          (type) => type.id === data!.protocolMapper,
        );

        return {
          config: {
            protocol: data.protocol,
            protocolMapper: data.protocolMapper,
          },
          mapping,
          data,
        };
      } else {
        const model = isOnClientScope
          ? await adminClient.clientScopes.findOne({ id })
          : await adminClient.clients.findOne({ id });
        if (!model) {
          throw new Error(t("common:notFound"));
        }
        const protocolMappers =
          serverInfo.protocolMapperTypes![model.protocol!];
        const mapping = protocolMappers.find(
          (mapper) => mapper.id === mapperId,
        );
        if (!mapping) {
          throw new Error(t("common:notFound"));
        }
        return {
          mapping,
          config: {
            protocol: model.protocol,
            protocolMapper: mapperId,
          },
        };
      }
    },
    ({ config, mapping, data }) => {
      setConfig(config);
      setMapping(mapping);
      if (data) {
        convertToFormValues(data, setValue);
      }
    },
    [],
  );

  const [toggleDeleteDialog, DeleteConfirm] = useConfirmDialog({
    titleKey: "common:deleteMappingTitle",
    messageKey: "common:deleteMappingConfirm",
    continueButtonLabel: "common:delete",
    continueButtonVariant: ButtonVariant.danger,
    onConfirm: async () => {
      try {
        if (isOnClientScope) {
          await adminClient.clientScopes.delProtocolMapper({
            id,
            mapperId,
          });
        } else {
          await adminClient.clients.delProtocolMapper({
            id,
            mapperId,
          });
        }
        addAlert(t("common:mappingDeletedSuccess"), AlertVariant.success);
        navigate(toDetails());
      } catch (error) {
        addError("common:mappingDeletedError", error);
      }
    },
  });

  const save = async (formMapping: ProtocolMapperRepresentation) => {
    const key = isUpdating ? "Updated" : "Created";
    try {
      const mapping = { ...config, ...convertFormValuesToObject(formMapping) };
      if (isUpdating) {
        isOnClientScope
          ? await adminClient.clientScopes.updateProtocolMapper(
              { id, mapperId },
              { id: mapperId, ...mapping },
            )
          : await adminClient.clients.updateProtocolMapper(
              { id, mapperId },
              { id: mapperId, ...mapping },
            );
      } else {
        isOnClientScope
          ? await adminClient.clientScopes.addProtocolMapper({ id }, mapping)
          : await adminClient.clients.addProtocolMapper({ id }, mapping);
      }
      addAlert(t(`common:mapping${key}Success`), AlertVariant.success);
    } catch (error) {
      addError(`common:mapping${key}Error`, error);
    }
  };

  return (
    <>
      <DeleteConfirm />
      <ViewHeader
        titleKey={isUpdating ? mapping?.name! : t("common:addMapper")}
        subKey={isUpdating ? mapperId : "client-scopes:addMapperExplain"}
        dropdownItems={
          isUpdating
            ? [
                <DropdownItem
                  key="delete"
                  value="delete"
                  onClick={toggleDeleteDialog}
                >
                  {t("common:delete")}
                </DropdownItem>,
              ]
            : undefined
        }
      />
      <PageSection variant="light">
        <FormAccess
          isHorizontal
          onSubmit={handleSubmit(save)}
          role="manage-clients"
        >
          <FormGroup label={t("common:mapperType")} fieldId="mapperType">
            <KeycloakTextInput
              type="text"
              id="mapperType"
              name="mapperType"
              isReadOnly
              value={mapping?.name}
            />
          </FormGroup>
          <FormGroup
            label={t("common:name")}
            labelIcon={
              <HelpItem
                helpText={t("client-scopes-help:mapperName")}
                fieldLabelId="name"
              />
            }
            fieldId="name"
            isRequired
            validated={
              errors.name ? ValidatedOptions.error : ValidatedOptions.default
            }
            helperTextInvalid={t("common:required")}
          >
            <KeycloakTextInput
              id="name"
              isReadOnly={isUpdating}
              validated={
                errors.name ? ValidatedOptions.error : ValidatedOptions.default
              }
              {...register("name", { required: true })}
            />
          </FormGroup>
          <FormProvider {...form}>
            <DynamicComponents
              properties={mapping?.properties || []}
              isNew={!isUpdating}
            />
          </FormProvider>
          <ActionGroup>
            <Button variant="primary" type="submit">
              {t("common:save")}
            </Button>
            <Button
              variant="link"
              component={(props) => <Link {...props} to={toDetails()} />}
            >
              {t("common:cancel")}
            </Button>
          </ActionGroup>
        </FormAccess>
      </PageSection>
    </>
  );
}
